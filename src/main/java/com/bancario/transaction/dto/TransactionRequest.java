package com.bancario.transaction.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransactionRequest(
        @NotBlank(message = "El ID de la cuenta no puede estar en blanco.")
        String accountId,

        @NotBlank(message = "El ID del cliente no puede estar en blanco.")
        String customerId,

        @NotNull(message = "El monto no puede ser nulo.")
        @Positive(message = "El monto debe ser un valor positivo.")
        BigDecimal amount,

        String description
) {}