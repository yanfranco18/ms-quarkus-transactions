package com.bancario.transaction.dto;

import java.math.BigDecimal;

public record AccountTransactionStatus(
        Integer freeTransactionLimit,
        Integer currentMonthlyTransactions,
        BigDecimal transactionFeeAmount
) {}
