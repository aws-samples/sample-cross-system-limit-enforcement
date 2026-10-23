package com.meridian.limit.legacy;

/**
 * Raised when the legacy daily-total cannot be obtained (API down, circuit
 * open, timeout). The limit read is a fail-closed dependency: this surfaces as
 * a hard error so a transfer is rejected rather than risking a limit breach.
 */
public class LegacyTotalUnavailableException extends RuntimeException {

    public LegacyTotalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
