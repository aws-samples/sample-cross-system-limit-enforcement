package com.meridian.limit.transfer;

import java.math.BigDecimal;

/**
 * Outbound transfer result.
 */
public record TransferResponse(
        String transferId,
        String reservationId,
        TransferOutcome outcome,
        BigDecimal amount,
        String currency) {
}
