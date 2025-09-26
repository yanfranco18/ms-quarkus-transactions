package com.bancario.transaction.dto;

import com.bancario.transaction.enums.AccountStatus;
import com.bancario.transaction.enums.AccountType;
import com.bancario.transaction.enums.CreditType;
import com.bancario.transaction.enums.ProductType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AccountResponse(
        String id,
        String customerId,
        String accountNumber,
        ProductType productType,
        AccountType accountType,
        CreditType creditType,
        BigDecimal balance,
        BigDecimal amountUsed, // <-- Nuevo campo 'amountUsed'
        LocalDateTime openingDate,
        Integer monthlyMovements,
        LocalDateTime specificDepositDate,
        AccountStatus status,
        List<String> holders,
        List<String> signatories
) {}