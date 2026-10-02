-- EX-1 — expense-service schema, owned by Flyway from the first deploy (STANDARDS D1, ddl-auto=validate).
-- Design: microservices/docs/slices/ex-1-direct-expense-voucher.md
--
-- Every column type is chosen to match its entity EXACTLY: under validate a mismatch is not a warning, it is a
-- service that does not start (@Lob+TEXT and ENUM-for-String both crash-looped services here before).
-- InnoDB stated on every table — row locks and FKs are the point (the MyISAM purchase table incident).

-- What the shop spends ON. The category is the owner's word; account_code is the ledger's.
CREATE TABLE IF NOT EXISTS expense_category (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    code             VARCHAR(32)   NOT NULL,
    name             VARCHAR(120)  NOT NULL,
    account_code     VARCHAR(16)   NOT NULL,     -- finance account; must be type EXPENSE (checked on save)
    active           BIT(1)        NOT NULL DEFAULT b'1',
    sort_order       INT           NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_category_org_code (organization_id, code),
    KEY idx_expense_category_org (organization_id, active, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One payment out. Three axes, never one status (the QuoteStatus lesson):
--   status          DRAFT | POSTED | VOIDED       — the document
--   posting_status  NONE | PENDING | POSTED_GL | FAILED — what the LEDGER has answered (stamped by the outbox)
CREATE TABLE IF NOT EXISTS expense_voucher (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    user_id          BIGINT        NULL,
    store_id         BIGINT        NULL,
    voucher_no       VARCHAR(20)   NULL,         -- EXP-000001, allocated LATE (at post) per org
    voucher_date     DATE          NOT NULL,
    paid_from        VARCHAR(16)   NOT NULL,     -- CASH | BANK (EX-1); DRAWER | AP | EMPLOYEE later
    payee_name       VARCHAR(160)  NULL,
    note             VARCHAR(500)  NULL,
    total            DECIMAL(19,2) NOT NULL,     -- STAMPED from the lines in the same transaction
    status           VARCHAR(16)   NOT NULL,
    posting_status   VARCHAR(16)   NOT NULL,
    posting_error    VARCHAR(500)  NULL,
    void_reason      VARCHAR(255)  NULL,
    voided_by        BIGINT        NULL,
    voided_at        DATETIME      NULL,
    posted_at        DATETIME      NULL,
    idempotency_key  VARCHAR(80)   NULL,
    version          INT           NOT NULL DEFAULT 0,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    -- DUP-1: the INDEX carries duplicate protection, not a pre-check. NULL keys are distinct in MySQL.
    UNIQUE KEY uq_expense_voucher_org_idem (organization_id, idempotency_key),
    UNIQUE KEY uq_expense_voucher_org_no (organization_id, voucher_no),
    KEY idx_expense_voucher_org_date (organization_id, voucher_date),
    KEY idx_expense_voucher_org_user_date (organization_id, user_id, voucher_date),
    KEY idx_expense_voucher_org_status (organization_id, status, voucher_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS expense_voucher_line (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    voucher_id       BIGINT        NOT NULL,
    line_no          INT           NOT NULL,
    category_id      BIGINT        NOT NULL,
    account_code     VARCHAR(16)   NOT NULL,     -- SNAPSHOT at post: re-mapping a category never rewrites history
    category_name    VARCHAR(120)  NULL,         -- snapshot, for the list and the printout
    description      VARCHAR(255)  NULL,
    amount           DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_expense_line_voucher (voucher_id),
    KEY idx_expense_line_category (category_id),
    CONSTRAINT fk_expense_line_voucher FOREIGN KEY (voucher_id) REFERENCES expense_voucher (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The ledger posting, captured in the voucher's own transaction (transactional outbox). The payload is the WHOLE
-- PostingEventRequest as JSON text, so a new contract field needs no new column — the field-by-field GlOutbox
-- in business/education is how 4200 Sales Discount stayed empty for weeks. VARCHAR, not JSON/TEXT/@Lob, to keep
-- the validate contract trivially true; a voucher is capped at 50 lines, which keeps the payload far below this.
CREATE TABLE IF NOT EXISTS expense_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    voucher_id       BIGINT        NOT NULL,
    event_type       VARCHAR(24)   NOT NULL,     -- EXPENSE | EXPENSE_REVERSAL
    event_key        VARCHAR(80)   NOT NULL,     -- EXP-<org>-<voucherId>-POST|VOID — finance dedups on it
    payload          VARCHAR(12000) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_outbox_event_key (event_key),
    KEY idx_expense_outbox_status (status, id),
    KEY idx_expense_outbox_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- EX-0c — per-org document counters, the same shape business (V45) and finance (V7) own. common-docnum's
-- DocumentNumberService reads and bumps it through OrgDocumentSeqRepo.
CREATE TABLE IF NOT EXISTS org_document_seq (
    organization_id  BIGINT        NOT NULL,
    doc_type         VARCHAR(16)   NOT NULL,
    next_val         BIGINT        NOT NULL DEFAULT 0,
    updated          DATETIME      DEFAULT NULL,
    PRIMARY KEY (organization_id, doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The shared audit trail's local outbox (common-audit AbstractAuditOutbox — lengths copied from the entity).
CREATE TABLE IF NOT EXISTS audit_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    action           VARCHAR(32)   NOT NULL,
    entity_type      VARCHAR(32)   NULL,
    entity_ref       VARCHAR(64)   NULL,
    amount           DECIMAL(19,2) NULL,
    details          VARCHAR(500)  NULL,
    reason           VARCHAR(255)  NULL,
    before_value     VARCHAR(64)   NULL,
    after_value      VARCHAR(64)   NULL,
    actor_org_id     BIGINT        NULL,
    actor_type       VARCHAR(24)   NULL,
    actor_email      VARCHAR(160)  NULL,
    event_key        VARCHAR(64)   NULL,
    occurred_at      DATETIME      NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500)  NULL,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    KEY idx_audit_outbox_pending (status, id),
    KEY idx_audit_outbox_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
