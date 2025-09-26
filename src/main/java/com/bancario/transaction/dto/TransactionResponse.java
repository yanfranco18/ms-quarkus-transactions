package com.bancario.transaction.dto;

import com.bancario.transaction.enums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        String id,
        String accountId,
        String customerId,
        TransactionType transactionType,
        BigDecimal amount,
        LocalDateTime transactionDate,
        String description
) {}