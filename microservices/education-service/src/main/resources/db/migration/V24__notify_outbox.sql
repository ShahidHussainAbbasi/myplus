-- Slice N1 — the notification outbox. Design: microservices/docs/slices/edu-N1-notification-outbox.md
--
-- ── Why an outbox and not a direct call ────────────────────────────────────────────────────────────
-- Before this table, `SubstitutionController.notifyCoverBestEffort` LOGGED instead of sending. The obvious
-- fix -- call EmailService from the hook -- is wrong three times over: it puts an inter-service HTTP call on
-- a write path, it loses the message entirely when notification-service is unreachable, and nothing records
-- that a teacher was never told they are covering a class.
--
-- A row here is written in the SAME transaction as the substitution, so the notice exists if and only if the
-- assignment does. Delivery happens AFTER_COMMIT, and a failure leaves the row PENDING for the scheduled
-- relay to re-drive. This is the THIRD use of the shared OutboxRelay in this service, after gl_outbox
-- (slice 0.1) and audit_outbox (slice 1.3) -- deliberately the same shape, no new state machine.
--
-- ── Why the recipient ADDRESS is stored, not a staff id ────────────────────────────────────────────
-- The relay runs on a schedule with no inbound request, so it must not need to resolve a domain record to
-- do its job. And the platform snapshots values at the moment of a decision -- report cards (1.5),
-- promotions (1.6) and guardian_portal_access.email (3.1) all do this -- so a later edit cannot silently
-- restate what was sent. Correcting a teacher's address does not redirect an already-queued notice; the
-- explicit act of re-assigning picks up the new one.
--
-- ── Why the rendered subject/body live here too ────────────────────────────────────────────────────
-- Same reason: the relay must be able to deliver a row without re-entering the domain. The text is purely
-- operational (class, period, date, room) and deliberately carries NO marks and NO behaviour data, because
-- outbox rows outlive the event they describe.
--
-- NOTE the columns this table does NOT have: no `recipient_name`, no `staff_id`, no `substitution_id`. The
-- outbox owns delivery, not the domain -- a foreign key back into the domain would invite queries that make
-- the outbox a second, worse copy of the substitution table.

CREATE TABLE IF NOT EXISTS notify_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    -- What happened, e.g. COVER_ASSIGNED. Free text rather than an enum column: adding a Java enum value to
    -- a MySQL enum needs an ALTER ... MODIFY, which ddl-auto will not do (platform lesson, "Data truncated").
    event_type       VARCHAR(64)   NOT NULL,
    recipient_email  VARCHAR(255)  NOT NULL,
    subject          VARCHAR(255)  NULL,
    body             VARCHAR(2000) NULL,
    -- One per event, so a retried delivery is recognisable downstream. Same convention as audit_outbox.
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
    KEY idx_notify_outbox_status (status, id),
    -- standard D3: every scoped column is indexed
    KEY idx_notify_outbox_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
