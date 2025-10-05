package com.bancario.transaction.service.impl;

import com.bancario.transaction.client.AccountServiceRestClient;
import com.bancario.transaction.dto.*;
import com.bancario.transaction.enums.AccountStatus;
import com.bancario.transaction.enums.CreditType;
import com.bancario.transaction.enums.ProductType;
import com.bancario.transaction.enums.TransactionType;
import com.bancario.transaction.exception.InsufficientFundsException;
import com.bancario.transaction.mapper.TransactionMapper;
import com.bancario.transaction.repository.TransactionRepository;
import com.bancario.transaction.repository.entity.Transaction;
import com.bancario.transaction.service.TransactionService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class TransactionServiceImpl implements TransactionService {

    @Inject
    TransactionRepository transactionRepository;

    @Inject
    TransactionMapper transactionMapper;

    @Inject
    @RestClient
    AccountServiceRestClient accountServiceRestClient;

    @Override
    public Uni<TransactionResponse> processDeposit(TransactionRequest request) {
        log.info("Processing deposit for account ID: {}", request.accountId());

        // 1. Obtener el estado de la cuenta para tarificación (DTO LIGERO)
        return accountServiceRestClient.getTransactionStatus(request.accountId())
                .onFailure().transform(e ->
                        // Esto captura errores como Cuenta No Encontrada o Cuenta No Pasiva del Account-Service
                        new IllegalArgumentException("Validation failed in Account Service: " + e.getMessage())
                )
                .onItem().transformToUni(status -> {

                    // 2. Aplicar la lógica de tarificación
                    BigDecimal fee = calculateFee(status); // Se asume que este método usa AccountTransactionStatus
                    BigDecimal netAmount = request.amount().subtract(fee); // Monto real a depositar

                    // 3. Ejecutar la transacción central (Actualiza el SALDO de forma atómica en el Core/Account-Service)
                    // Se usa el monto NETO
                    return executeCoreTransaction(request.accountId(), netAmount, fee, TransactionType.DEPOSIT)
                            .onItem().transformToUni(coreResult -> {

                                if (!coreResult.success()) {
                                    return Uni.createFrom().failure(new IllegalStateException("Core banking transaction failed."));
                                }

                                // 4. Actualizar el contador (Llama al PATCH atómico, Fire and Forget)
                                notifyAccountService(request.accountId());

                                // 5. Persistir el registro localmente, incluyendo el externalReference
                                return persistLocalTransaction(request, coreResult.coreTransactionId(), netAmount, fee, TransactionType.DEPOSIT);
                            });
                });
    }

    @Override
    public Uni<TransactionResponse> processWithdrawal(TransactionRequest request) {
        log.info("Processing withdrawal for account ID: {}", request.accountId());

        // 1. OBTENER Cuenta Completa
        return accountServiceRestClient.getAccountById(request.accountId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Account not found with ID: " + request.accountId()))
                .onItem().transformToUni(account -> {

                    validatePassiveAccount(account);

                    // 2. APLICAR TARIFICACIÓN y CALCULAR DÉBITO TOTAL
                    BigDecimal fee = calculateFeeFromAccount(account);
                    // Monto total a DEBITAR del balance (Monto solicitado + Comisión)
                    BigDecimal totalDebitAmount = request.amount().add(fee); // <-- Ejemplo: 100.50

                    // 3. VALIDACIÓN DE SALDO (Compara con 100.50)
                    if (account.balance().compareTo(totalDebitAmount) < 0) {
                        log.warn("RETIRO RECHAZADO: Saldo insuficiente. Cuenta: " + request.accountId());
                        return Uni.createFrom().failure(new InsufficientFundsException("Insufficient funds. Cannot withdraw " + totalDebitAmount + "."));
                    }

                    // 4. EJECUTAR CORE (Actualiza el SALDO de forma atómica)
                    // ¡CORRECCIÓN CLAVE! Pasar el MONTO TOTAL A DEBITAR (totalDebitAmount) como NEGATIVO.
                    return executeCoreTransaction(
                            request.accountId(),
                            totalDebitAmount.negate(), // <-- ¡AQUÍ ESTÁ LA CORRECCIÓN! (-100.50)
                            fee,
                            TransactionType.WITHDRAWAL
                    )
                            .onItem().transformToUni(coreResult -> {

                                if (!coreResult.success()) {
                                    return Uni.createFrom().failure(new IllegalStateException("Core banking transaction failed."));
                                }

                                // 5. ACTUALIZAR CONTADOR (Fire and Forget)
                                notifyAccountService(request.accountId());

                                // 6. PERSISTIR registro local
                                // Se registra el monto solicitado original (100.00) en negativo.
                                return persistLocalTransaction(request, coreResult.coreTransactionId(), request.amount().negate(), fee, TransactionType.WITHDRAWAL);
                            });
                });
    }

    @Override
    public Uni<TransactionResponse> processPayment(TransactionRequest request) {
        log.info("Processing payment for credit product ID: {}", request.accountId());
        return accountServiceRestClient.getAccountById(request.accountId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Credit product not found with ID: " + request.accountId()))
                .onItem().transformToUni(account -> {
                    // Llama al método de validación
                    validateActiveAccount(account);

                    // Validar si hay deuda pendiente
                    if (account.amountUsed().compareTo(BigDecimal.ZERO) == 0) {
                        return Uni.createFrom().failure(new IllegalArgumentException("No tiene deuda pendiente."));
                    }
                    if (account.amountUsed().compareTo(request.amount()) < 0) {
                        return Uni.createFrom().failure(new IllegalArgumentException("El pago excede el monto de la deuda pendiente."));
                    }
                    // Lógica corregida: Pasas el monto directamente al método updateAccount
                    AccountResponse updatedAccount = updateAccount(account, request.amount(), TransactionType.PAYMENT);
                    return processTransaction(updatedAccount, request, TransactionType.PAYMENT);
                });
    }

    @Override
    public Uni<TransactionResponse> processConsumption(TransactionRequest request) {
        log.info("Processing consumption for credit card ID: {}", request.accountId());
        return accountServiceRestClient.getAccountById(request.accountId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Account not found with ID: " + request.accountId()))
                .onItem().transformToUni(account -> {
                    validateConsumption(account, request.amount());
                    AccountResponse updatedAccount = updateAccount(account, request.amount(), TransactionType.CONSUMPTION);
                    log.info("Processing update for credit card of AccountResponse: {}", updatedAccount);
                    return processTransaction(updatedAccount, request, TransactionType.CONSUMPTION);
                });
    }

    @Override
    public Multi<TransactionResponse> findByAccountId(String accountId) {
        log.info("Searching for movements for account ID: {}", accountId);

        // Se usa el repositorio (asumiendo que devuelve Uni<List<Transaction>>)
        Uni<List<Transaction>> uniEntities = transactionRepository.findByAccountId(accountId);

        // Se transforma el flujo de entidades a un flujo de DTOs usando el mapper
        return uniEntities
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("No transactions found for account ID: " + accountId))
                .onItem().transformToMulti(entities -> {
                    if (entities.isEmpty()) {
                        return Multi.createFrom().empty();
                    }
                    return Multi.createFrom().iterable(entities);
                })
                // *** Se utiliza el mapper inyectado: MapStruct hace la magia ***
                .onItem().transform(transactionMapper::toResponse);
    }

    private void validateActiveAccount(AccountResponse account) {
        if (account.productType() != ProductType.ACTIVE) {
            throw new IllegalArgumentException("This service only processes transactions for active accounts.");
        }
        if (account.status() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Account is not active.");
        }
    }

    private void validatePassiveAccount(AccountResponse account) {
        if (account.productType() != ProductType.PASSIVE) {
            throw new IllegalArgumentException("This service only processes transactions for passive accounts.");
        }
        if (account.status() != AccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Account is not active.");
        }
    }

    private void validateConsumption(AccountResponse account, BigDecimal amount) {
        log.debug("Validating consumption for account ID: {}", account.id());

        // Validación 1: El producto debe ser activo(credito)
        if (account.productType() != ProductType.ACTIVE) {
            log.error("Product type is not ACTIVE: {}", account.productType());
            throw new IllegalArgumentException("Consumption is only allowed for active products.");
        }

        // Validación 2: El producto debe ser una tarjeta de crédito
        if (account.creditType() != CreditType.CREDIT_CARD) {
            log.error("Credit type is not CREDIT_CARD: {}", account.creditType());
            throw new IllegalArgumentException("Consumption is only allowed for credit card products.");
        }

        // Validación 3: La cuenta debe estar activa
        if (account.status() != AccountStatus.ACTIVE) {
            log.error("Account is not active: {}", account.id());
            throw new IllegalArgumentException("Account is not active and cannot process consumptions.");
        }

        // Validación 4: El consumo no debe exceder el límite de crédito disponible
        BigDecimal availableLimit = account.balance().subtract(account.amountUsed());
        if (amount.compareTo(availableLimit) > 0) {
            log.error("Consumption amount exceeds available limit. Amount: {}, Available: {}", amount, availableLimit);
            throw new IllegalArgumentException("El consumo excede el límite de crédito disponible.");
        }
    }

    private AccountResponse updateAccount(AccountResponse account, BigDecimal amount, TransactionType transactionType) {
        BigDecimal newBalance = account.balance();
        BigDecimal newAmountUsed = account.amountUsed();

        if (account.productType() == ProductType.PASSIVE) {
            if (transactionType == TransactionType.DEPOSIT) {
                newBalance = newBalance.add(amount);
            } else if (transactionType == TransactionType.WITHDRAWAL) {
                newBalance = newBalance.subtract(amount);
            }
        } else if (account.productType() == ProductType.ACTIVE) {
            if (transactionType == TransactionType.PAYMENT) {
                newAmountUsed = newAmountUsed.subtract(amount);
            } else if (transactionType == TransactionType.CONSUMPTION) {
                newAmountUsed = newAmountUsed.add(amount);
            }
        }

        return new AccountResponse(
                account.id(),
                account.customerId(),
                account.accountNumber(),
                account.productType(),
                account.accountType(),
                account.creditType(),
                account.status(),
                account.openingDate(),
                // --- Estado Financiero (Campos actualizados o mantenidos) ---
                newBalance,   // Nuevo saldo/límite (Actualizado si es PASIVO)
                newAmountUsed,// Nuevo monto usado (Actualizado si es ACTIVO)

                // --- Configuración y Monitoreo (Mantenidos sin cambios) ---
                account.maintenanceFeeAmount(),     // Costo de mantenimiento
                account.requiredDailyAverage(),     // Promedio requerido
                account.freeTransactionLimit(),     // Límite transacciones GRATUITAS
                account.transactionFeeAmount(),     // Costo de la comisión
                account.currentMonthlyTransactions(), // Contador de transacciones
                account.monthlyMovements(),         // Movimientos mensuales
                account.specificDepositDate(),      // Fecha de depósito específica
                // --- Titulares y Firmantes (Mantenidos) ---
                account.holders(),
                account.signatories()
        );
    }

    private Uni<TransactionResponse> processTransaction(AccountResponse updatedAccount, TransactionRequest request, TransactionType transactionType) {
        return accountServiceRestClient.updateAccountBalance(updatedAccount.id(), updatedAccount)
                .chain(result -> {
                    Transaction transaction = transactionMapper.toEntity(request);
                    transaction.setTransactionType(transactionType);
                    transaction.setTransactionDate(LocalDateTime.now());
                    return transactionRepository.persist(transaction)
                            .onItem().transform(persistedTransaction -> transactionMapper.toResponse(persistedTransaction));
                });
    }

    /** Calcula la comisión basada en los límites usando AccountResponse. */
    private BigDecimal calculateFeeFromAccount(AccountResponse account) {
        Integer current = account.currentMonthlyTransactions();
        Integer limit = account.freeTransactionLimit();
        BigDecimal feeAmount = account.transactionFeeAmount();

        // Aseguramos que los valores existan y aplicamos la lógica
        if (current == null || limit == null || feeAmount == null) {
            return BigDecimal.ZERO;
        }

        // Si el contador actual es MAYOR o IGUAL al límite gratuito, aplica la tarifa.
        if (current >= limit) {
            return feeAmount;
        }
        return BigDecimal.ZERO; // Transacción gratuita
    }

    /** * Calcula la comisión basada en los límites.
     * NOTA: Usa el DTO ligero AccountTransactionStatus (Opción A).
     */
    private BigDecimal calculateFee(AccountTransactionStatus status) {
        Integer current = status.currentMonthlyTransactions();
        Integer limit = status.freeTransactionLimit();
        BigDecimal feeAmount = status.transactionFeeAmount();
        // Aseguramos que los valores existan y aplicamos la lógica
        if (current == null || limit == null || feeAmount == null) {
            return BigDecimal.ZERO;
        }
        // Si el contador actual es MAYOR o IGUAL al límite gratuito, aplica la tarifa.
        if (current >= limit) {
            return feeAmount;
        }
        return BigDecimal.ZERO; // Transacción gratuita
    }

    /** Notifica al Account-Service de forma asíncrona para incrementar el contador. */
    private void notifyAccountService(String accountId) {
        accountServiceRestClient.incrementTransactions(accountId)
                .subscribe().with(
                        success -> log.info("Contador incrementado exitosamente para cuenta: {}", accountId),
                        failure -> log.error("Fallo al incrementar contador para cuenta {}: {}", accountId, failure.getMessage())
                );
    }

    // Método SIMULADO que ahora SÍ LAMA AL ENDPOINT DE SALDO DEL Account-Service
    private Uni<CoreTransactionResult> executeCoreTransaction(String accountId, BigDecimal netAmount, BigDecimal fee, TransactionType type) {
        log.info("CORE TRANSACTION SIMULATED ({}) for Account {}. Net Amount: {}. Fee: {}", type, accountId, netAmount, fee);

        // 1. Obtener la cuenta para calcular el nuevo saldo (temporalmente necesario para la simulación)
        return accountServiceRestClient.getAccountById(accountId)
                .onItem().transform(account -> {
                    // 2. Calcular el nuevo balance
                    BigDecimal currentBalance = account.balance();
                    BigDecimal newBalance = currentBalance.add(netAmount); // netAmount ya es positivo para depósito, negativo para retiro

                    // 3. Crear el AccountResponse actualizado
                    // NOTA: Usamos el método updateAccount solo para calcular y generar el DTO correcto
                    AccountResponse updatedAccount = new AccountResponse(
                            account.id(), account.customerId(), account.accountNumber(),
                            account.productType(), account.accountType(), account.creditType(),
                            account.status(), account.openingDate(), newBalance, account.amountUsed(), // <-- Nuevo Balance
                            account.maintenanceFeeAmount(), account.requiredDailyAverage(),
                            account.freeTransactionLimit(), account.transactionFeeAmount(),
                            account.currentMonthlyTransactions(), account.monthlyMovements(),
                            account.specificDepositDate(), account.holders(), account.signatories()
                    );
                    return updatedAccount;
                })
                // 4. Llamar al endpoint de actualización de saldo del Account-Service
                .onItem().transformToUni(updatedAccount ->
                        accountServiceRestClient.updateAccountBalance(accountId, updatedAccount)
                )
                // 5. Retornar el resultado de la simulación
                .onItem().transform(finalAccount ->
                        new CoreTransactionResult(UUID.randomUUID().toString(), true, finalAccount.balance())
                )
                .onFailure().transform(e -> new RuntimeException("Fallo en la simulación del Core/updateAccountBalance: " + e.getMessage()));
    }

    // Método para persistir la transacción localmente (asumiendo que está bien mapeado)
    private Uni<TransactionResponse> persistLocalTransaction(TransactionRequest request, String coreId, BigDecimal netAmount, BigDecimal fee, TransactionType type) {
        Transaction transaction = transactionMapper.toEntity(request);
        transaction.setTransactionType(type);
        transaction.setTransactionDate(LocalDateTime.now());
        transaction.setAmount(netAmount);
        transaction.setDescription(request.description() + (fee.compareTo(BigDecimal.ZERO) > 0 ? " (Fee: " + fee + ")" : ""));
        transaction.setExternalReference(coreId);

        return transactionRepository.persist(transaction)
                .onItem().transform(transactionMapper::toResponse);
    }
}