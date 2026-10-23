package com.meridian.limit.transfer;

import com.meridian.limit.config.LimitEnforcementProperties;
import com.meridian.limit.legacy.CoreExecutionResult;
import com.meridian.limit.legacy.LegacyCoreClient;
import com.meridian.limit.reservation.ReservationStatus;
import com.meridian.limit.reservation.TransferReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Orchestrates the online transfer flow described in the design doc:
 * reserve (short txn) -> call the core (no txn) -> commit/release (short txn).
 *
 * <p>On a core timeout the reservation is deliberately left RESERVED so the
 * reconciler can determine the outcome later. We never guess about money.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final ReservationService reservationService;
    private final LegacyCoreClient legacyCoreClient;
    private final LimitEnforcementProperties props;

    public TransferService(ReservationService reservationService,
                           LegacyCoreClient legacyCoreClient,
                           LimitEnforcementProperties props) {
        this.reservationService = reservationService;
        this.legacyCoreClient = legacyCoreClient;
        this.props = props;
    }

    public TransferResponse transfer(TransferRequest request) {
        String currency = request.currency() != null ? request.currency() : props.getCurrency();
        String transferId = request.transferId();
        String customerId = request.customerId();
        BigDecimal amount = request.amount();

        // Idempotent short-circuit for an already-settled/known transfer.
        TransferReservation reservation;
        try {
            reservation = reservationService.reserve(transferId, customerId, amount, currency);
        } catch (LimitExceededException e) {
            log.info("Transfer {} rejected: {}", transferId, e.getMessage());
            return new TransferResponse(transferId, null, TransferOutcome.LIMIT_EXCEEDED, amount, currency);
        }

        // If this transferId was already processed, don't re-execute against core.
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            return new TransferResponse(transferId, reservation.getReservationId(),
                    mapExisting(reservation.getStatus()), amount, currency);
        }

        // Call the legacy core OUTSIDE any DB transaction (the gap lock is released).
        CoreExecutionResult result = legacyCoreClient.execute(transferId, customerId, amount, currency);

        return switch (result) {
            case SUCCESS -> {
                reservationService.commit(reservation.getReservationId());
                yield new TransferResponse(transferId, reservation.getReservationId(),
                        TransferOutcome.COMMITTED, amount, currency);
            }
            case FAILURE -> {
                reservationService.release(reservation.getReservationId());
                yield new TransferResponse(transferId, reservation.getReservationId(),
                        TransferOutcome.REJECTED_BY_CORE, amount, currency);
            }
            case TIMEOUT -> {
                // Leave RESERVED; the reconciler decides later. Never guess.
                log.warn("Transfer {} timed out; left RESERVED for reconciliation", transferId);
                yield new TransferResponse(transferId, reservation.getReservationId(),
                        TransferOutcome.PENDING_RECONCILIATION, amount, currency);
            }
        };
    }

    private TransferOutcome mapExisting(ReservationStatus status) {
        return switch (status) {
            case COMMITTED -> TransferOutcome.COMMITTED;
            case RELEASED -> TransferOutcome.REJECTED_BY_CORE;
            case EXPIRED -> TransferOutcome.REJECTED_BY_CORE;
            case RESERVED -> TransferOutcome.PENDING_RECONCILIATION;
        };
    }
}
