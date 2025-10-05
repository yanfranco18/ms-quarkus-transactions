package com.bancario.transaction.dto;

import java.math.BigDecimal;

public record CoreTransactionResult(
        String coreTransactionId,
        boolean success,
        BigDecimal finalAmount) {}
