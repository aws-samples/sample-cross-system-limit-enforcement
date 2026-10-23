package com.meridian.limit.transfer;

import com.meridian.limit.config.LimitEnforcementProperties;
import com.meridian.limit.config.LimitEnforcementProperties.ReadSource;
import com.meridian.limit.legacy.CachingLegacyTotalReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Composes the running total from its additive terms. The legacy total is one
 * additive term consulted through a single adapter, gated by a config flag:
 *
 * <pre>
 *   COMPOSED: runningTotal = legacyTotal + cloudActive   (Phase 1/2)
 *   CLOUD:    runningTotal =               cloudActive   (Phase 3+)
 * </pre>
 *
 * Flipping {@code read-source} to CLOUD stops calling the legacy adapter; in
 * Phase 4 the adapter and this branch can be deleted with nothing else changing.
 *
 * <p>{@code cloudActive} (RESERVED + COMMITTED) is passed in by the caller,
 * which reads it under the gap lock.
 */
@Component
public class RunningTotalComposer {

    private final CachingLegacyTotalReader legacyTotalReader;
    private final LimitEnforcementProperties props;

    public RunningTotalComposer(CachingLegacyTotalReader legacyTotalReader,
                                LimitEnforcementProperties props) {
        this.legacyTotalReader = legacyTotalReader;
        this.props = props;
    }

    /**
     * @param cloudActive the cloud RESERVED+COMMITTED sum, read under the gap lock
     * @return the composed running total for this customer/day/currency
     */
    public BigDecimal compose(String customerId, LocalDate txnDate, String currency, BigDecimal cloudActive) {
        if (props.getReadSource() == ReadSource.CLOUD) {
            return cloudActive;
        }
        // COMPOSED: legacy total is fail-closed (throws if unavailable).
        BigDecimal legacyTotal = legacyTotalReader.read(customerId, txnDate, currency);
        return legacyTotal.add(cloudActive);
    }
}
