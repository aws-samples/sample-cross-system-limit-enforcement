package com.meridian.limit.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single transfer attempt. The set of these rows IS the ledger; the running
 * total is derived from them and never stored separately.
 */
@Entity
@Table(name = "transfer_reservation")
public class TransferReservation {

    @Id
    @Column(name = "reservation_id", length = 36, nullable = false, updatable = false)
    private String reservationId;

    @Column(name = "transfer_id", length = 64, nullable = false, updatable = false)
    private String transferId;

    @Column(name = "customer_id", length = 32, nullable = false, updatable = false)
    private String customerId;

    @Column(name = "txn_date", nullable = false, updatable = false)
    private LocalDate txnDate;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 8, nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    protected TransferReservation() {
        // for JPA
    }

    private TransferReservation(String reservationId, String transferId, String customerId,
                                LocalDate txnDate, BigDecimal amount, String currency,
                                Instant createdAt, Instant expiresAt) {
        this.reservationId = reservationId;
        this.transferId = transferId;
        this.customerId = customerId;
        this.txnDate = txnDate;
        this.amount = amount;
        this.currency = currency;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Create a fresh RESERVED reservation. */
    public static TransferReservation reserve(String reservationId, String transferId, String customerId,
                                              LocalDate txnDate, BigDecimal amount, String currency,
                                              Instant now, Instant expiresAt) {
        return new TransferReservation(reservationId, transferId, customerId, txnDate, amount,
                currency, now, expiresAt);
    }

    /** Core confirmed settlement. */
    public void commit(Instant now) {
        this.status = ReservationStatus.COMMITTED;
        this.committedAt = now;
    }

    /** Core rejected; no money moved. */
    public void release() {
        this.status = ReservationStatus.RELEASED;
    }

    /** Reconciliation confirmed no settlement (or hit the hard safety window). */
    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }
}
