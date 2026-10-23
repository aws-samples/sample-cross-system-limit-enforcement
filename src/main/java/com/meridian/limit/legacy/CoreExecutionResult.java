package com.meridian.limit.legacy;

/**
 * Outcome of asking the legacy core to execute a transfer.
 *
 * <p>{@link #TIMEOUT} is deliberately distinct from {@link #FAILURE}: on a
 * timeout the money may or may not have moved, so the reservation is left
 * RESERVED for the reconciler rather than released.
 */
public enum CoreExecutionResult {
    SUCCESS,
    FAILURE,
    TIMEOUT
}
