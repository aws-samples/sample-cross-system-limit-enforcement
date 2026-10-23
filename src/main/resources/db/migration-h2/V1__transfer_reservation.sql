-- H2 (MySQL mode) variant of the reservation ledger. Same shape as the
-- Aurora/MySQL migration, using H2-friendly index declarations.
CREATE TABLE transfer_reservation (
    reservation_id   CHAR(36)      NOT NULL,
    transfer_id      VARCHAR(64)   NOT NULL,
    customer_id      VARCHAR(32)   NOT NULL,
    txn_date         DATE          NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    currency         VARCHAR(8)    NOT NULL,
    status           VARCHAR(12)   NOT NULL,
    created_at       TIMESTAMP(6)  NOT NULL,
    expires_at       TIMESTAMP(6)  NOT NULL,
    committed_at     TIMESTAMP(6)  NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT uq_transfer_id UNIQUE (transfer_id)
);

CREATE INDEX idx_running_total ON transfer_reservation (customer_id, txn_date, status);
CREATE INDEX idx_reconcile ON transfer_reservation (status, expires_at);
