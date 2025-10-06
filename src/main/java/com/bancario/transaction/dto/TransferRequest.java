package com.bancario.transaction.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Solicitud de datos para iniciar una transferencia entre cuentas.
 * Solo requiere los números de cuenta (origen y destino) y el monto.
 */
public record TransferRequest(

        @NotBlank(message = "Source account number is required.")
        String sourceAccountNumber,   // Número de cuenta de ORIGEN
        @NotBlank(message = "Target account number is required.")
        String targetAccountNumber,   // Número de cuenta de DESTINO
        @NotNull(message = "Amount is required.")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero.")
        BigDecimal amount,            // Monto a transferir
        String description            // Descripción de la transferencia (opcional)
) {}