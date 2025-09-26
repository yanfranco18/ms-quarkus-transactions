package com.bancario.transaction.service.impl;

import com.bancario.transaction.client.AccountServiceRestClient;
import com.bancario.transaction.dto.AccountResponse;
import com.bancario.transaction.dto.TransactionRequest;
import com.bancario.transaction.dto.TransactionResponse;
import com.bancario.transaction.enums.AccountStatus;
import com.bancario.transaction.enums.CreditType;
import com.bancario.transaction.enums.ProductType;
import com.bancario.transaction.enums.TransactionType;
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
        return accountServiceRestClient.getAccountById(request.accountId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Account not found with ID: " + request.accountId()))
                .onItem().transformToUni(account -> {
                    // Validar que la cuenta es pasiva y activa
                    validatePassiveAccount(account);

                    AccountResponse updatedAccount = updateAccount(account, request.amount(), TransactionType.DEPOSIT);

                    return processTransaction(updatedAccount, request, TransactionType.DEPOSIT);
                });
    }

    @Override
    public Uni<TransactionResponse> processWithdrawal(TransactionRequest request) {
        log.info("Processing withdrawal for account ID: {}", request.accountId());
        return accountServiceRestClient.getAccountById(request.accountId())
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Account not found with ID: " + request.accountId()))
                .onItem().transformToUni(account -> {
                    // Validar que la cuenta es pasiva y activa
                    validatePassiveAccount(account);

                    if (account.balance().compareTo(request.amount()) < 0) {
                        return Uni.createFrom().failure(new IllegalArgumentException("Insufficient funds. Cannot withdraw more than available balance."));
                    }

                    AccountResponse updatedAccount = updateAccount(account, request.amount(), TransactionType.WITHDRAWAL);

                    return processTransaction(updatedAccount, request, TransactionType.WITHDRAWAL);
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
                newBalance,
                newAmountUsed,
                account.openingDate(),
                account.monthlyMovements(),
                account.specificDepositDate(),
                account.status(),
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
}