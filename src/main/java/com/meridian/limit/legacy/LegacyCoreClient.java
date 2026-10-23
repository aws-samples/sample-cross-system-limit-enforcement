package com.meridian.limit.legacy;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The seam to the legacy estate. Three capabilities are exposed to the cloud:
 * a read-only daily-total API, a gateway to execute transfers, and a status
 * API used during reconciliation.
 *
 * <p>The legacy daily total is treated as an opaque scalar; we never reach into
 * the legacy database or reason about its internals.
 */
public interface LegacyCoreClient {

    /**
     * How much this customer has moved through the legacy core today, in the
     * given currency. Callers must decide the failure posture (fail-closed).
     */
    BigDecimal fetchDailyTotal(String customerId, LocalDate txnDate, String currency);

    /** Ask the core to execute a transfer. Distinguishes timeout from failure. */
    CoreExecutionResult execute(String transferId, String customerId, BigDecimal amount, String currency);

    /** Query the core's settlement status for a transfer (reconciliation). */
    CoreSettlementStatus fetchStatus(String transferId);
}
