package com.meridian.limit.reservation;

/**
 * Lifecycle of a transfer reservation.
 *
 * <p>{@link #RESERVED} and {@link #COMMITTED} count toward the running total.
 * {@link #RELEASED} and {@link #EXPIRED} do not.
 */
public enum ReservationStatus {

    /** Within the limit and inserted; counts immediately, before the core call. */
    RESERVED,

    /** Core confirmed the money moved. Still counts. */
    COMMITTED,

    /** Core rejected it; no money moved. Stops counting. */
    RELEASED,

    /** Timed out and reconciliation confirmed no settlement. Stops counting. */
    EXPIRED
}
