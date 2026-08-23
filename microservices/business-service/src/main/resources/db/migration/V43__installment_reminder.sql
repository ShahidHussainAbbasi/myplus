-- INST-3a — the chase log behind the collections worklist.
--
-- WHY THIS IS A RECORD AND NOT AN OUTBOX
-- The customer chose "worklist first, no sending yet" (design slices/inst-3a-reminder-scanner.md §1), because
-- the sale screen collects a name and a PHONE NUMBER and nothing else: `customer.email` exists as a column but
-- the installment sale panel never asks for one, and `Channel.SMS` is a deliberate no-op pending a provider
-- decision. A reminder queue shipped on email alone would have created rows, dead-lettered every one, and
-- reported green throughout.
--
-- So there are deliberately no `status`, `attempts`, `last_error` or `posted_at` columns. An outbox is a queue
-- of things to SEND; with no transport those columns describe nothing, and INST-3b would inherit a table whose
-- columns lie. What the shop actually lacks is not a queue but an answer to "have we already rung this person,
-- and what did they say?" — which is NOT derivable, and is the reason the existing Installments screen (which
-- can already compute who is overdue right now) is not enough on its own.
--
-- WHAT THIS DELIBERATELY DOES NOT ADD
-- No `overdue` flag on `installment`. INST-1 established that overdue is (due_date < today AND outstanding > 0),
-- derived on read, so the screen and the reminder can never disagree. This table records that we NOTICED —
-- a different fact from being overdue, and one that legitimately has a date.

CREATE TABLE IF NOT EXISTS installment_reminder (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    -- Copied FROM THE PLAN, never from a request context: the scanner runs on a @Scheduled thread where there
    -- is no authenticated user and every findScoped helper in this service is meaningless. See the design's
    -- §3 — the scanner's cross-tenant licence stops at the scanner, and every read-back path is scoped again.
    organization_id     BIGINT       DEFAULT NULL,

    plan_id             BIGINT       NOT NULL,
    installment_id      BIGINT       NOT NULL,

    -- Denormalised so the worklist renders without a second lookup per row. Safe to copy because a reminder,
    -- like an invoice, is a record of a moment — unlike the Installments worklist, which resolves the customer
    -- name live because it is a view of who the customer IS today.
    customer_id         BIGINT       DEFAULT NULL,

    -- DUE_SOON | OVERDUE. VARCHAR, not ENUM: adding a value to a MySQL ENUM needs ALTER ... MODIFY, and without
    -- it the insert fails with "Data truncated" — the trap that has already cost this platform a migration.
    stage               VARCHAR(16)  NOT NULL,

    due_date            DATE         NOT NULL,

    -- planNo/seqNo/stage. See the UNIQUE constraint below for why the length and shape are what they are.
    dedupe_key          VARCHAR(120) NOT NULL,

    -- When the scanner first saw it. NOT when it became due, and NOT when it was rung.
    noticed_at          DATETIME     NOT NULL,

    -- The half that makes this a collections tool rather than a list. Null until somebody actually rings.
    acted_at            DATETIME     DEFAULT NULL,
    outcome             VARCHAR(32)  DEFAULT NULL,
    note                VARCHAR(255) DEFAULT NULL,

    PRIMARY KEY (id),

    -- ONE ROW PER INSTALLMENT PER STAGE, enforced by the database rather than by the scanner remembering.
    -- The scanner is a timer: it will run again in five minutes, after a restart, and twice at once during a
    -- rolling deploy. This constraint is what makes all three harmless.
    --
    -- The key contains the STAGE, never the date the scan ran — so an installment that goes part-paid and
    -- falls behind again does not produce a second OVERDUE row and the shop is not told to ring twice.
    --
    -- VARCHAR(120) matches `notification_broadcast.dedupe_key` in shape AND length on purpose: INST-3b passes
    -- this string straight through to NotificationClient.sendEmail(..., dedupeKey) where it is already
    -- enforced by a UNIQUE constraint of its own. A transport plugs in; nothing here is redesigned.
    CONSTRAINT uq_installment_reminder_dedupe UNIQUE (dedupe_key),

    -- The worklist's only query: this org, this stage, most urgent first.
    KEY idx_reminder_org_stage (organization_id, stage, due_date),
    KEY idx_reminder_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
