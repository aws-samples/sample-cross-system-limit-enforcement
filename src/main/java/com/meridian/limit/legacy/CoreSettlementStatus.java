package com.meridian.limit.legacy;

/**
 * Result of querying the legacy core's status API for a transfer during
 * reconciliation.
 */
public enum CoreSettlementStatus {
    /** Core confirms the money moved. */
    SETTLED,
    /** Core reports the transfer as not found / failed; no money moved. */
    NOT_SETTLED,
    /** Core could not give a definitive answer yet. Never guess about money. */
    UNKNOWN
}
