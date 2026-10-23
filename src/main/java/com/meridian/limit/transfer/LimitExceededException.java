package com.meridian.limit.transfer;

import java.math.BigDecimal;

/**
 * Thrown from the reservation transaction when the transfer would breach the
 * daily limit. Rolls the transaction back so no RESERVED row persists.
 */
public class LimitExceededException extends RuntimeException {

    private final BigDecimal limit;
    private final BigDecimal runningTotal;
    private final BigDecimal amount;

    public LimitExceededException(BigDecimal limit, BigDecimal runningTotal, BigDecimal amount) {
        super("Transfer of %s would breach daily limit %s (running total %s)"
                .formatted(amount, limit, runningTotal));
        this.limit = limit;
        this.runningTotal = runningTotal;
        this.amount = amount;
    }

    public BigDecimal getLimit() {
        return limit;
    }

    public BigDecimal getRunningTotal() {
        return runningTotal;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
