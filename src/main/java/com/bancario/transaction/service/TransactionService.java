package com.bancario.transaction.service;

import com.bancario.transaction.dto.TransactionRequest;
import com.bancario.transaction.dto.TransactionResponse;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

public interface TransactionService {

    /**
     * Processes a deposit transaction.
     *
     * @param request The details of the deposit.
     * @return A Uni that emits the processed transaction response.
     */
    Uni<TransactionResponse> processDeposit(TransactionRequest request);

    /**
     * Processes a withdrawal transaction.
     *
     * @param request The details of the withdrawal.
     * @return A Uni that emits the processed transaction response.
     */
    Uni<TransactionResponse> processWithdrawal(TransactionRequest request);

    /**
     * Processes a payment for a credit product.
     *
     * @param request The details of the payment.
     * @return A Uni that emits the processed transaction response.
     */
    Uni<TransactionResponse> processPayment(TransactionRequest request);

    /**
     * Processes a consumption charge for a credit card, updating the amount used.
     *
     * @param request The transaction details.
     * @return A Uni that emits the processed transaction response.
     */
    Uni<TransactionResponse> processConsumption(TransactionRequest request);

    /**
     * Busca todos los movimientos para una cuenta específica.
     * @param accountId El ID de la cuenta.
     * @return Flujo reactivo (Multi) de TransactionResponse.
     */
    Multi<TransactionResponse> findByAccountId(String accountId);
}