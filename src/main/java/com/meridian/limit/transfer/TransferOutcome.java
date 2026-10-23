package com.meridian.limit.transfer;

/**
 * Result of processing a transfer request.
 */
public enum TransferOutcome {
    /** Core confirmed settlement; reservation COMMITTED. */
    COMMITTED,
    /** Would breach the daily limit; nothing reserved, no money moved. */
    LIMIT_EXCEEDED,
    /** Core rejected; reservation RELEASED, no money moved. */
    REJECTED_BY_CORE,
    /** Core call timed out; reservation left RESERVED for the reconciler. */
    PENDING_RECONCILIATION
}
