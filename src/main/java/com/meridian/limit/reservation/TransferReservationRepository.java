package com.meridian.limit.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransferReservationRepository extends JpaRepository<TransferReservation, String> {

    /**
     * The cloud portion of the running total, taken WITH a pessimistic write
     * lock. On MySQL/Aurora this issues {@code SELECT ... FOR UPDATE}, acquiring
     * a gap lock on the {@code (customer_id, txn_date)} range so that two
     * concurrent transfers for the same customer serialize at the database.
     *
     * <p>RESERVED rows are included, so in-flight transfers count toward the
     * total and can't both slip past the limit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
            FROM TransferReservation r
            WHERE r.customerId = :customerId
              AND r.txnDate = :txnDate
              AND r.currency = :currency
              AND r.status IN (com.meridian.limit.reservation.ReservationStatus.RESERVED,
                               com.meridian.limit.reservation.ReservationStatus.COMMITTED)
            """)
    BigDecimal sumActiveForUpdate(@Param("customerId") String customerId,
                                  @Param("txnDate") LocalDate txnDate,
                                  @Param("currency") String currency);

    /** Non-locking read of the cloud total, for reporting/observability. */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0)
            FROM TransferReservation r
            WHERE r.customerId = :customerId
              AND r.txnDate = :txnDate
              AND r.currency = :currency
              AND r.status IN (com.meridian.limit.reservation.ReservationStatus.RESERVED,
                               com.meridian.limit.reservation.ReservationStatus.COMMITTED)
            """)
    BigDecimal sumActive(@Param("customerId") String customerId,
                         @Param("txnDate") LocalDate txnDate,
                         @Param("currency") String currency);

    Optional<TransferReservation> findByTransferId(String transferId);

    /**
     * Claim a batch of expired RESERVED rows for reconciliation. Uses
     * {@code FOR UPDATE SKIP LOCKED} so multiple reconciler pods claim disjoint
     * row sets with no application-level coordination.
     */
    // Locking is expressed inline in the SQL (FOR UPDATE SKIP LOCKED); Spring
    // Data's @Lock cannot be applied to native queries.
    @Query(value = """
            SELECT * FROM transfer_reservation
            WHERE status = 'RESERVED' AND expires_at < :now
            ORDER BY expires_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<TransferReservation> claimExpiredForReconciliation(@Param("now") Instant now,
                                                            @Param("batchSize") int batchSize);

    /**
     * H2-compatible claim without {@code SKIP LOCKED} (H2 does not support it).
     * Used only under local/dev profiles for end-to-end testing; production runs
     * on Aurora and uses {@link #claimExpiredForReconciliation}.
     */
    @Query(value = """
            SELECT * FROM transfer_reservation
            WHERE status = 'RESERVED' AND expires_at < :now
            ORDER BY expires_at
            LIMIT :batchSize
            FOR UPDATE
            """, nativeQuery = true)
    List<TransferReservation> claimExpiredForReconciliationNoSkipLocked(@Param("now") Instant now,
                                                                        @Param("batchSize") int batchSize);
}
