package com.bancario.transaction.service.impl;

import com.bancario.transaction.client.AccountServiceRestClient;
import com.bancario.transaction.dto.*;
import com.bancario.transaction.enums.AccountStatus;
import com.bancario.transaction.enums.CreditType;
import com.bancario.transaction.enums.ProductType;
import com.bancario.transaction.enums.TransactionType;
import com.bancario.transaction.exception.InsufficientFundsException;
import com.bancario.transaction.exception.TransferIncompleteException;
import com.bancario.transaction.mapper.TransactionMapper;
import com.bancario.transaction.repository.TransactionRepository;
import com.bancario.transaction.repository.entity.Transaction;
import com.bancario.transaction.service.TransactionService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
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

    /**
     * Procesa una transferencia de fondos entre dos cuentas, actuando como un orquestador.
     * * La transferencia sigue un proceso atómico:
     * 1. Resuelve los IDs de cuenta de origen y destino usando sus números.
     * 2. Valida el estado 'ACTIVE' de ambas cuentas.
     * 3. Ejecuta un retiro (WITHDRAWAL) interno en la cuenta de origen (incluye validación de saldo).
     * 4. Si el retiro es exitoso, ejecuta un depósito (DEPOSIT) interno en la cuenta de destino.
     * 5. Maneja los fallos, incluyendo la necesidad de lógica de compensación si el depósito falla
     * después de un retiro exitoso.
     * * @param request Datos de la transferencia (números de cuenta, monto, descripción).
     * @return Uni<TransactionResponse> Respuesta consolidada de la transferencia.
     * @throws IllegalArgumentException Si una de las cuentas no se encuentra o no está activa.
     * @throws InsufficientFundsException Si la cuenta de origen no tiene saldo suficiente.
     * @throws RuntimeException Si hay fallos en la comunicación con el Account-Service o en el depósito.
     */
    @Override
    public Uni<TransactionResponse> processTransfer(TransferRequest request) {
        log.info("TRANSFERENCIA INICIADA: De {} a {} por {}",
                request.sourceAccountNumber(), request.targetAccountNumber(), request.amount());

        // 1. OBTENER CUENTAS POR NÚMERO
        Uni<AccountResponse> sourceAccountUni = accountServiceRestClient.getAccountByNumber(request.sourceAccountNumber());
        Uni<AccountResponse> targetAccountUni = accountServiceRestClient.getAccountByNumber(request.targetAccountNumber());

        // Combinar los resultados de ambas búsquedas en un solo flujo
        return Uni.combine().all().unis(sourceAccountUni, targetAccountUni)
                .asTuple()
                // Manejo de errores 404/NotFoundException del REST Client
                .onFailure().transform(e -> {
                    if (e instanceof NotFoundException) {
                        log.error("Transferencia fallida: Una de las cuentas no fue encontrada. Detalles: {}", e.getMessage());
                        return new IllegalArgumentException("Source or target account not found. Check account numbers.");
                    }
                    return new RuntimeException("Failed to retrieve account details for transfer.", e);
                })
                .onItem().transformToUni(tuple -> {

                    AccountResponse sourceAccount = tuple.getItem1();
                    AccountResponse targetAccount = tuple.getItem2();

                    // VALIDACIONES DE ESTADO DE CUENTA
                    if (sourceAccount.status() != AccountStatus.ACTIVE || targetAccount.status() != AccountStatus.ACTIVE) {
                        log.error("TRANSFERENCIA FALLIDA: Una o ambas cuentas no están activas. Origen: {}, Destino: {}",
                                sourceAccount.status(), targetAccount.status());
                        return Uni.createFrom().failure(new IllegalArgumentException("One or both accounts are not active for transfer."));
                    }
                    log.info("Cuentas validadas: Origen ID: {}, Destino ID: {}", sourceAccount.id(), targetAccount.id());

                    // 2. PREPARAR Y EJECUTAR RETIRO (CUENTA DE ORIGEN)
                    TransactionRequest withdrawalRequest = new TransactionRequest(
                            sourceAccount.id(),
                            sourceAccount.customerId(),
                            request.amount(),
                            "Transferencia enviada a " + targetAccount.accountNumber() + ": " + request.description()
                    );

                    log.info("Iniciando fase de retiro interno para cuenta de origen: {}", sourceAccount.id());
                    // Llamada a la versión interna, pasando la cuenta de origen.
                    return processWithdrawalInternal(withdrawalRequest, sourceAccount)

                            // Manejo de fallo en retiro (ej. Saldo insuficiente).
                            .onFailure().transform(e -> {
                                if (e instanceof InsufficientFundsException) {
                                    log.warn("Transferencia rechazada: Saldo insuficiente en cuenta de origen {}.", sourceAccount.id());
                                }
                                return e;
                            })
                            .onItem().transformToUni(withdrawalResponse -> {

                                // Retiro exitoso.
                                // 3. PREPARAR Y EJECUTAR DEPÓSITO (CUENTA DE DESTINO)
                                TransactionRequest depositRequest = new TransactionRequest(
                                        targetAccount.id(),
                                        targetAccount.customerId(),
                                        request.amount(),
                                        "Transferencia recibida de " + sourceAccount.accountNumber() + ": " + request.description()
                                );

                                log.info("Retiro exitoso. Procediendo a depósito en cuenta destino: {}", targetAccount.id());

                                return processDepositInternal(depositRequest, targetAccount)

                                        // *** LÓGICA DE COMPENSACIÓN (REVERSIÓN) ***
                                        .onFailure().recoverWithUni(depositFailure -> {
                                            log.error("FALLO CRÍTICO EN DEPÓSITO: Transferencia fallida en destino {}. Iniciando reversión...", targetAccount.id(), depositFailure);

                                            // Creamos la solicitud de reversión (DEPÓSITO a la cuenta ORIGEN).
                                            TransactionRequest reversalRequest = new TransactionRequest(
                                                    sourceAccount.id(),
                                                    sourceAccount.customerId(),
                                                    request.amount(),
                                                    "REVERSION: Fallo en transferencia a " + targetAccount.accountNumber()
                                            );

                                            // Ejecutamos la reversión (Depósito Interno a la cuenta de ORIGEN).
                                            return processDepositInternal(reversalRequest, sourceAccount)
                                                    .onFailure().invoke(reversalFailure -> {
                                                        // ¡ALERTA CRÍTICA! Si la reversión falla, se necesita intervención manual URGENTE.
                                                        log.error("¡ALERTA CRÍTICA! La reversión a la cuenta de origen {} también falló.", sourceAccount.id(), reversalFailure);
                                                    })
                                                    .onItem().transformToUni(reversalSuccess -> {
                                                        // La reversión fue exitosa.
                                                        log.info("REVERSIÓN EXITOSA: Saldo restaurado en cuenta de origen {}.", sourceAccount.id());
                                                        // Lanzamos la excepción para el GlobalMapper (500)
                                                        throw new TransferIncompleteException(
                                                                String.format("Transfer failed: Deposit to target account %s failed, but withdrawal was successfully reverted.",
                                                                        targetAccount.accountNumber())
                                                        );
                                                    });
                                        })
                                        // Fin de la compensación

                                        .onItem().transform(depositResponse -> {

                                            log.info("TRANSFERENCIA EXITOSA: De {} a {} por {}", request.sourceAccountNumber(), request.targetAccountNumber(), request.amount());

                                            // 4. RESPUESTA CONSOLIDADA
                                            return TransactionResponse.builder()
                                                    .accountId(sourceAccount.id())
                                                    .id(UUID.randomUUID().toString())
                                                    .customerId(sourceAccount.customerId())
                                                    .transactionType(TransactionType.TRANSFER)
                                                    .amount(request.amount())
                                                    .transactionDate(depositResponse.transactionDate())
                                                    .description("Transferencia exitosa a " + targetAccount.accountNumber())
                                                    .externalReference(depositResponse.externalReference())
                                                    .build();
                                        });
                            });
                });
    }

    /**
     * Versión interna de retiro utilizada durante la transferencia.
     * Recibe la cuenta ya cargada para evitar llamadas REST redundantes.
     * @param request La solicitud de retiro (monto, descripción).
     * @param account El objeto AccountResponse de la cuenta de origen (incluye saldo y reglas).
     * @return Uni<TransactionResponse> El resultado de la transacción (simulado el core).
     * @throws InsufficientFundsException Si la cuenta no tiene saldo suficiente.
     */
    private Uni<TransactionResponse> processWithdrawalInternal(TransactionRequest request, AccountResponse account) {
        log.info("Processing internal withdrawal for account ID: {}", request.accountId());

        // 1. APLICAR TARIFICACIÓN y CALCULAR DÉBITO TOTAL
        // NOTA: Se asume que calculateFeeFromAccount(account) devuelve la comisión
        BigDecimal fee = calculateFeeFromAccount(account);
        // Monto total a DEBITAR del balance (Monto solicitado + Comisión)
        BigDecimal totalDebitAmount = request.amount().add(fee);

        // 2. VALIDACIÓN DE SALDO (Usando el balance ya cargado: account.balance())
        if (account.balance().compareTo(totalDebitAmount) < 0) {
            log.warn("RETIRO RECHAZADO: Saldo insuficiente. Cuenta: {}. Requiere: {}, Disponible: {}",
                    request.accountId(), totalDebitAmount, account.balance());
            return Uni.createFrom().failure(new InsufficientFundsException("Insufficient funds. Cannot withdraw " + totalDebitAmount + "."));
        }

        // 3. EJECUTAR CORE (Simulación: Actualizar el SALDO de forma atómica en el Account-Service)
        // El core requiere el MONTO TOTAL A DEBITAR como NEGATIVO.
        return executeCoreTransaction(
                request.accountId(),
                totalDebitAmount.negate(),
                fee,
                TransactionType.WITHDRAWAL
        )
                .onItem().transformToUni(coreResult -> {

                    if (!coreResult.success()) {
                        return Uni.createFrom().failure(new IllegalStateException("Core banking transaction failed for withdrawal."));
                    }

                    // 4. ACTUALIZAR CONTADOR y PERSISTIR
                    notifyAccountService(request.accountId()); // Fire and Forget
                    // Persistir el registro local con el monto solicitado original en negativo.
                    return persistLocalTransaction(request, coreResult.coreTransactionId(), request.amount().negate(), fee, TransactionType.WITHDRAWAL);
                });
    }

    /**
     * Versión interna de depósito utilizada durante la transferencia.
     * Recibe la cuenta ya cargada para aplicar tarificación.
     * @param request La solicitud de depósito (monto, descripción).
     * @param account El objeto AccountResponse de la cuenta de destino (incluye reglas).
     * @return Uni<TransactionResponse> El resultado de la transacción (simulado el core).
     */
    private Uni<TransactionResponse> processDepositInternal(TransactionRequest request, AccountResponse account) {
        log.info("Processing internal deposit for account ID: {}", request.accountId());

        // 1. Aplicar la lógica de tarificación
        // NOTA: Se asume que calculateFeeFromAccount(account) devuelve la comisión
        BigDecimal fee = calculateFeeFromAccount(account);
        // Monto real a depositar (Monto solicitado - Comisión)
        BigDecimal netAmount = request.amount().subtract(fee);

        // 2. Ejecutar CORE (Simulación: Actualiza el SALDO de forma atómica en el Account-Service)
        // Se usa el monto NETO
        return executeCoreTransaction(
                request.accountId(),
                netAmount, // Monto positivo
                fee,
                TransactionType.DEPOSIT
        )
                .onItem().transformToUni(coreResult -> {

                    if (!coreResult.success()) {
                        return Uni.createFrom().failure(new IllegalStateException("Core banking transaction failed for deposit."));
                    }

                    // 3. Actualizar CONTADOR y PERSISTIR
                    notifyAccountService(request.accountId()); // Fire and Forget

                    // 4. Persistir el registro localmente, usando el monto neto.
                    return persistLocalTransaction(request, coreResult.coreTransactionId(), netAmount, fee, TransactionType.DEPOSIT);
                });
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

                    // 3. Retornar el AccountResponse actualizado directamente (CORRECCIÓN DEL WARNING)
                    return new AccountResponse(
                            account.id(), account.customerId(), account.accountNumber(),
                            account.productType(), account.accountType(), account.creditType(),
                            account.status(), account.openingDate(), newBalance, account.amountUsed(),
                            account.maintenanceFeeAmount(), account.requiredDailyAverage(),
                            account.freeTransactionLimit(), account.transactionFeeAmount(),
                            account.currentMonthlyTransactions(), account.monthlyMovements(),
                            account.specificDepositDate(), account.holders(), account.signatories()
                    );
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