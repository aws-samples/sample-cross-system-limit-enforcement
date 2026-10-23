package com.meridian.limit.legacy.stub;

import com.meridian.limit.legacy.CoreExecutionResult;
import com.meridian.limit.legacy.CoreSettlementStatus;
import com.meridian.limit.legacy.LegacyCoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the legacy core, active only under the
 * {@code stub-legacy} profile. Lets us drive the full flow end to end without a
 * real mainframe:
 *
 * <ul>
 *   <li>per-customer seeded legacy daily totals,</li>
 *   <li>a configurable execution outcome (SUCCESS / FAILURE / TIMEOUT),</li>
 *   <li>a settlement-status map used by reconciliation.</li>
 * </ul>
 *
 * State is mutated through {@link StubLegacyCoreController} so a test script can
 * set up each scenario over HTTP.
 */
@Component
@Profile("stub-legacy")
public class StubLegacyCore implements LegacyCoreClient {

    private static final Logger log = LoggerFactory.getLogger(StubLegacyCore.class);

    private final Map<String, BigDecimal> legacyTotals = new ConcurrentHashMap<>();
    private final Map<String, CoreSettlementStatus> settlementByTransfer = new ConcurrentHashMap<>();

    private volatile CoreExecutionResult nextResult = CoreExecutionResult.SUCCESS;
    private volatile boolean totalUnavailable = false;

    @Override
    public BigDecimal fetchDailyTotal(String customerId, LocalDate txnDate, String currency) {
        if (totalUnavailable) {
            throw new com.meridian.limit.legacy.LegacyTotalUnavailableException(
                    "stub: legacy total forced unavailable", null);
        }
        return legacyTotals.getOrDefault(customerId, BigDecimal.ZERO);
    }

    @Override
    public CoreExecutionResult execute(String transferId, String customerId, BigDecimal amount, String currency) {
        CoreExecutionResult r = nextResult;
        log.info("stub core execute transfer={} customer={} amount={} -> {}", transferId, customerId, amount, r);
        // Record a default settlement view consistent with the execution result.
        switch (r) {
            case SUCCESS -> settlementByTransfer.putIfAbsent(transferId, CoreSettlementStatus.SETTLED);
            case FAILURE -> settlementByTransfer.putIfAbsent(transferId, CoreSettlementStatus.NOT_SETTLED);
            case TIMEOUT -> settlementByTransfer.putIfAbsent(transferId, CoreSettlementStatus.UNKNOWN);
        }
        return r;
    }

    @Override
    public CoreSettlementStatus fetchStatus(String transferId) {
        return settlementByTransfer.getOrDefault(transferId, CoreSettlementStatus.UNKNOWN);
    }

    // --- control surface (used by the controller) ----------------------------

    void setLegacyTotal(String customerId, BigDecimal total) {
        legacyTotals.put(customerId, total);
    }

    void setNextResult(CoreExecutionResult result) {
        this.nextResult = result;
    }

    void setTotalUnavailable(boolean unavailable) {
        this.totalUnavailable = unavailable;
    }

    void setSettlement(String transferId, CoreSettlementStatus status) {
        settlementByTransfer.put(transferId, status);
    }

    void reset() {
        legacyTotals.clear();
        settlementByTransfer.clear();
        nextResult = CoreExecutionResult.SUCCESS;
        totalUnavailable = false;
    }
}
