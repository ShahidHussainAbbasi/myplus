-- audit-service baseline: the append-only audit trail. Insert + read only — rows are never updated or deleted.
-- Idempotent ingestion: unique (organization_id, event_key) so a retried outbox delivery is a no-op.

CREATE TABLE audit_event (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    source_service   VARCHAR(32)   NULL,       -- business | finance | inventory | ...
    action           VARCHAR(32)   NOT NULL,   -- SALE | VOID_SALE | RECEIPT | PAYMENT | ...
    entity_type      VARCHAR(32)   NULL,       -- INVOICE | BILL | CUSTOMER | VENDOR
    entity_ref       VARCHAR(64)   NULL,       -- invoiceNo / purchaseInvoiceNo / voucher
    amount           DECIMAL(19,2) NULL,
    details          VARCHAR(500)  NULL,
    event_key        VARCHAR(64)   NULL,       -- producer-generated UUID; dedup key
    occurred_at      DATETIME      NULL,       -- when the event happened (producer clock)
    received_at      DATETIME      NULL,       -- when audit-service persisted it
    PRIMARY KEY (id),
    CONSTRAINT uq_audit_org_eventkey UNIQUE (organization_id, event_key),
    KEY idx_audit_org_id (organization_id, id)
);
