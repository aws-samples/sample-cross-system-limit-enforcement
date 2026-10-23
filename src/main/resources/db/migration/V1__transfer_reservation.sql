-- Reservation ledger. This is the ONLY state we keep.
-- No counter column, no daily-total row, no midnight reset job.
-- The running total is always derived as a live SUM over this table.
CREATE TABLE transfer_reservation (
    reservation_id   CHAR(36)      NOT NULL,
    transfer_id      VARCHAR(64)   NOT NULL,   -- idempotency key from caller
    customer_id      VARCHAR(32)   NOT NULL,
    txn_date         DATE          NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    currency         VARCHAR(8)    NOT NULL,
    status           VARCHAR(12)   NOT NULL,   -- RESERVED | COMMITTED | RELEASED | EXPIRED
    created_at       DATETIME(6)   NOT NULL,
    expires_at       DATETIME(6)   NOT NULL,
    committed_at     DATETIME(6)   NULL,
    PRIMARY KEY (reservation_id),
    -- The one index the running-total query rides on.
    KEY idx_running_total (customer_id, txn_date, status),
    -- Idempotency: one reservation per business transfer id.
    UNIQUE KEY uq_transfer_id (transfer_id),
    -- Reconciler lookup: expired rows still in RESERVED.
    KEY idx_reconcile (status, expires_at)
);
