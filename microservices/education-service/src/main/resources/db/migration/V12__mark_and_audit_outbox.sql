-- Slice 1.3 — marks entry.
-- Design: microservices/docs/slices/edu-1.3-marks-entry.md
--
-- `mark` is the first table on this platform holding a claim about a CHILD that follows them for years.
-- A wrong fee is refunded; a wrong mark is discovered at university admission. Hence the UNIQUE
-- constraint here and the audit trail below.
--
-- UNIQUE (exam_paper_id, student_enroll_no) is enforced by the DATABASE, not just by the upsert code:
-- the constraint is what makes it true under concurrency, so a double-clicked Save cannot produce two
-- marks for one child.
--
-- marks_obtained is NULLABLE on purpose (D2): absent is a first-class state, NOT zero. Zero means "sat
-- the paper and scored nothing"; absent means they did not sit it. Conflating them corrupts every
-- average 1.4 computes and every report card 1.5 prints, and cannot be recovered afterwards.
--
-- `audit_outbox` mirrors business-service's table and education's own gl_outbox (slice 0.1): capture in
-- the caller's transaction, deliver AFTER_COMMIT, retry via the shared OutboxRelay. A marks save must
-- never fail because audit-service is unreachable, and the event must never be lost either.
--
-- Indexes per DB standard D3. Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS mark (
    mark_id            BIGINT       NOT NULL AUTO_INCREMENT,
    exam_paper_id      BIGINT       NOT NULL,
    student_enroll_no  VARCHAR(255) NOT NULL,
    -- NULL when absent — see D2. Never 0 to mean "did not sit".
    marks_obtained     INT          NULL,
    absent             BIT(1)       NOT NULL DEFAULT b'0',
    remarks            VARCHAR(255) NULL,
    user_id            BIGINT       NOT NULL,
    organization_id    BIGINT       NULL,
    dated              DATETIME     NULL,
    updated            DATETIME     NULL,
    PRIMARY KEY (mark_id),
    -- one mark per student per paper, enforced by the DB (D1)
    UNIQUE KEY uk_mark_paper_student (exam_paper_id, student_enroll_no),
    KEY idx_mark_org (organization_id),
    KEY idx_mark_paper (exam_paper_id),
    -- 1.5's transcript read: every mark for one student in one tenant
    KEY idx_mark_org_student (organization_id, student_enroll_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    action           VARCHAR(255)  NOT NULL,
    entity_type      VARCHAR(255)  NULL,
    entity_ref       VARCHAR(255)  NULL,
    -- for MARK_CHANGED this carries the OLD and NEW values: an audit recording only the new number
    -- cannot answer "was this altered?", which is the one question anyone actually asks of it.
    details          VARCHAR(1000) NULL,
    event_key        VARCHAR(255)  NULL,
    occurred_at      DATETIME      NULL,
    status           VARCHAR(255)  NOT NULL DEFAULT 'PENDING',
    attempts         INT           NULL DEFAULT 0,
    last_error       VARCHAR(1000) NULL,
    organization_id  BIGINT        NULL,
    user_id          BIGINT        NULL,
    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,
    PRIMARY KEY (id),
    -- the relay's work queue: findTop100ByStatusOrderByIdAsc('PENDING')
    KEY idx_audit_outbox_status (status, id),
    KEY idx_audit_outbox_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
