package com.bancario.transaction.dto;

import com.bancario.transaction.enums.ProductType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO optimizado para el reporte de comisiones.
 * Contiene los campos mínimos necesarios para la agrupación y suma.
 */
public record CommissionReportDto(
        String accountId,
        // Tipo general (PASSIVE/ACTIVE)
        ProductType productType,
        // Nombre detallado (ej: SAVINGS_ACCOUNT, CREDIT_CARD). Usado para la agrupación final.
        String productName,
        BigDecimal fee,
        LocalDateTime transactionDate
) {}