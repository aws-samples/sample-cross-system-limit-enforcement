package com.meridian.limit.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Inbound transfer request. {@code transferId} is the idempotency key.
 */
public record TransferRequest(
        @NotNull @Size(max = 64) String transferId,
        @NotNull @Size(max = 32) String customerId,
        @NotNull @Positive @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 8) String currency) {
}
