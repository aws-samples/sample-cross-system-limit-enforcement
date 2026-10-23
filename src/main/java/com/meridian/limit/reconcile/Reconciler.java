package com.meridian.limit.reconcile;

import com.meridian.limit.config.LimitEnforcementProperties;
import com.meridian.limit.legacy.CoreSettlementStatus;
import com.meridian.limit.legacy.LegacyCoreClient;
import com.meridian.limit.reservation.TransferReservation;
import com.meridian.limit.reservation.TransferReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Reconciles RESERVED rows whose TTL has passed. The TTL is not a death
 * sentence for a reservation — it's the trigger to investigate.
 *
 * <p>Rule: capacity is released only when we have a definitive answer about
 * whether money moved. The one exception is the hard safety window, after which
 * a stuck reservation is force-expired (and alerted) so a customer can never be
 * locked out of transfers forever.
 */
@Service
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final TransferReservationRepository repository;
    private final LegacyCoreClient legacyCoreClient;
    private final LimitEnforcementProperties props;
    private final Clock clock;

    public Reconciler(TransferReservationRepository repository,
                      LegacyCoreClient legacyCoreClient,
                      LimitEnforcementProperties props,
                      Clock clock) {
        this.repository = repository;
        this.legacyCoreClient = legacyCoreClient;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Claim and reconcile one batch of expired reservations. Runs in a single
     * transaction so the {@code FOR UPDATE SKIP LOCKED} claim holds for the
     * batch; multiple pods claim disjoint sets.
     *
     * @return the number of rows examined in this batch
     */
    @Transactional
    public int reconcileBatch() {
        Instant now = clock.instant();
        int batchSize = props.getReconciler().getBatchSize();
        List<TransferReservation> claimed = props.getReconciler().isSkipLocked()
                ? repository.claimExpiredForReconciliation(now, batchSize)
                : repository.claimExpiredForReconciliationNoSkipLocked(now, batchSize);

        for (TransferReservation r : claimed) {
            reconcileOne(r, now);
        }
        if (!claimed.isEmpty()) {
            log.info("Reconciler processed {} reservation(s)", claimed.size());
        }
        return claimed.size();
    }

    private void reconcileOne(TransferReservation r, Instant now) {
        CoreSettlementStatus status = legacyCoreClient.fetchStatus(r.getTransferId());
        switch (status) {
            case SETTLED -> {
                // The money moved after all; it correctly still counted.
                r.commit(now);
                log.info("Reconciled transfer {} as SETTLED -> COMMITTED", r.getTransferId());
            }
            case NOT_SETTLED -> {
                r.expire();
                log.warn("Reconciled transfer {} as NOT_SETTLED -> EXPIRED (alert)", r.getTransferId());
            }
            case UNKNOWN -> {
                // Still no definitive answer. Only force-expire past the hard
                // safety window; otherwise leave RESERVED and try again later.
                Instant hardDeadline = r.getCreatedAt().plus(props.getReservationMaxAge());
                if (now.isAfter(hardDeadline)) {
                    r.expire();
                    log.error("Transfer {} unresolved past safety window -> force EXPIRED (alert; operator review)",
                            r.getTransferId());
                } else {
                    log.warn("Transfer {} still UNKNOWN; leaving RESERVED for retry", r.getTransferId());
                }
            }
        }
    }
}
