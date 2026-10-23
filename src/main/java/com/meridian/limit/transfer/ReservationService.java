package com.meridian.limit.transfer;

import com.meridian.limit.config.LimitEnforcementProperties;
import com.meridian.limit.reservation.ReservationStatus;
import com.meridian.limit.reservation.TransferReservation;
import com.meridian.limit.reservation.TransferReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the short, transactional pieces of the reserve -> commit/release
 * lifecycle. Each public method here is a single database transaction; the
 * (potentially slow) core call happens between them, outside any transaction.
 */
@Service
public class ReservationService {

    private final TransferReservationRepository repository;
    private final RunningTotalComposer runningTotalComposer;
    private final CustomerLimitProvider limitProvider;
    private final LimitEnforcementProperties props;
    private final Clock clock;

    public ReservationService(TransferReservationRepository repository,
                              RunningTotalComposer runningTotalComposer,
                              CustomerLimitProvider limitProvider,
                              LimitEnforcementProperties props,
                              Clock clock) {
        this.repository = repository;
        this.runningTotalComposer = runningTotalComposer;
        this.limitProvider = limitProvider;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Steps 1-3 of the online flow, in one short transaction:
     * <ol>
     *   <li>read the cloud active sum WITH a gap lock ({@code FOR UPDATE}),</li>
     *   <li>compose the running total (legacy + cloud, per config),</li>
     *   <li>check the limit; if OK, insert a RESERVED row and commit.</li>
     * </ol>
     * The gap lock serializes concurrent same-customer transfers so two can't
     * both slip past the limit.
     *
     * @throws LimitExceededException if the transfer would breach the limit
     *         (transaction rolls back, no row persists)
     */
    @Transactional
    public TransferReservation reserve(String transferId, String customerId,
                                       BigDecimal amount, String currency) {
        // Idempotency: if we've already seen this transferId, return it as-is.
        Optional<TransferReservation> existing = repository.findByTransferId(transferId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        LocalDate txnDate = LocalDate.ofInstant(now, ZoneOffset.UTC);

        // (1) lock + read the cloud portion of the total for this customer/day.
        BigDecimal cloudActive = repository.sumActiveForUpdate(customerId, txnDate, currency);

        // (2) compose with the legacy leg (fail-closed if legacy is unavailable).
        BigDecimal runningTotal = runningTotalComposer.compose(customerId, txnDate, currency, cloudActive);

        // (3) check.
        BigDecimal limit = limitProvider.dailyLimitFor(customerId);
        if (runningTotal.add(amount).compareTo(limit) > 0) {
            throw new LimitExceededException(limit, runningTotal, amount);
        }

        TransferReservation reservation = TransferReservation.reserve(
                UUID.randomUUID().toString(),
                transferId,
                customerId,
                txnDate,
                amount,
                currency,
                now,
                now.plus(props.getReservationTtl()));
        return repository.save(reservation);
    }

    /** Mark a reservation COMMITTED (core confirmed settlement). */
    @Transactional
    public void commit(String reservationId) {
        TransferReservation r = load(reservationId);
        if (r.getStatus() == ReservationStatus.RESERVED) {
            r.commit(clock.instant());
        }
    }

    /** Mark a reservation RELEASED (core rejected; no money moved). */
    @Transactional
    public void release(String reservationId) {
        TransferReservation r = load(reservationId);
        if (r.getStatus() == ReservationStatus.RESERVED) {
            r.release();
        }
    }

    private TransferReservation load(String reservationId) {
        return repository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reservation not found: " + reservationId));
    }
}
