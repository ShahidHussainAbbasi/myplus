-- Audit #5: idempotency on money operations. A shared record dedups a whole operation on (org, operation, key), so a
-- double-click / retry / network-replay of receivePayment / payVendor / addPurchase can't double-charge or double-stock.
-- Plus a stable event_key on the GL outbox so a duplicate delivery to finance is a no-op (closes the #4 debt).

CREATE TABLE idempotency_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NULL,
    operation        VARCHAR(64)  NOT NULL,
    idem_key         VARCHAR(191) NOT NULL,
    result_ref       VARCHAR(191) NULL,
    created_at       DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_idem_org_op_key UNIQUE (organization_id, operation, idem_key)
);

ALTER TABLE gl_outbox
    ADD COLUMN event_key VARCHAR(64) NULL;
