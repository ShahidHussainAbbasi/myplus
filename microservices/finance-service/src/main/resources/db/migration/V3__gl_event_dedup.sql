-- Audit #5: idempotent GL posting. business-service stamps each outbox event with a unique event_key; finance claims
-- it once per (org, event_key). A duplicate delivery (outbox re-send) is a no-op → closes the #4 duplicate-journal
-- window. A SALE posts two journals (main + COGS) under ONE event_key, so dedup lives here, not on journal_entries.

CREATE TABLE gl_processed_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NULL,
    event_key        VARCHAR(64)  NOT NULL,
    created_at       DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gl_event_org_key UNIQUE (organization_id, event_key)
);
