-- Slice 3.5 — school notices & circulars.
-- Design: microservices/docs/slices/edu-3.5-notices.md
--
-- ── One table, and note what is NOT in it: the recipients ──────────────────────────────────────────
-- The audience is a FILTER (audience + optional grade_id), resolved from live enrolment at read time and
-- at send time. There is deliberately no notice_recipient table: a stored recipient list is a copy of an
-- access decision, and it goes wrong the moment a child transfers, a guardian link is corrected or a
-- student leaves. 3.1 D1 refused a stored child list for the same reason, and on a SAFETY notice a stale
-- list is worse than anywhere else in this system.
--
-- ── Why education owns this table at all (decision D-8, user-confirmed 2026-08-07) ────────────────
-- The programme originally routed notices through campaign-service. That service is a marketing engine:
-- its AudienceMember carries unsubscribed_at, and a guardian cannot unsubscribe from "school closed
-- tomorrow". Honouring the flag would silently drop a family from mandatory messages; ignoring it would
-- make the field a lie in a shared service. campaign-service remains the right composition for real
-- CAMPAIGNS (admissions drives, fee-reminder runs) — option C.
--
-- ── The record is the deliverable, not the email ───────────────────────────────────────────────────
-- Slices 3.1 and 3.3 put two authenticated surfaces in front of families, so a notice is now a record the
-- portals render and email is one delivery of it. An emailed-only notice is unrecoverable to a family that
-- deleted it, and "we sent it" vs "we never got it" is exactly the dispute a record settles.
--
-- status/audience are MySQL enums against @Enumerated(STRING): a new value needs ALTER ... MODIFY.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS notice (
    notice_id       BIGINT       NOT NULL AUTO_INCREMENT,
    title           VARCHAR(255) NOT NULL,
    -- A BOUNDED varchar, matching every other long-text column in this service
    -- (behaviour_note.description and homework.instructions are VARCHAR(2000)). TEXT was the first cut and
    -- broke startup: the entity used @Lob, Hibernate maps that to CLOB and validates it against MySQL as
    -- tinytext, so schema validation refused a TEXT column. 4000 chars is longer than any circular a school
    -- writes and keeps the row inline.
    body            VARCHAR(4000) NOT NULL,
    -- WHO it reaches. ONE_CLASS is the only value that reads grade_id.
    audience        ENUM('WHOLE_SCHOOL','GUARDIANS','STUDENTS','ONE_CLASS') NOT NULL DEFAULT 'WHOLE_SCHOOL',
    grade_id        BIGINT       NULL,
    -- DRAFT reaches nobody. There is no approval chain: 2.5 established this domain does not want one,
    -- and a notice needs exactly one boundary — not yet visible, versus visible and delivered.
    status          ENUM('DRAFT','PUBLISHED') NOT NULL DEFAULT 'DRAFT',
    published_on    DATE         NULL,
    -- A notice a school wants held at the top (exam week, a closure) — a date, not a flag, so it stops
    -- being pinned on its own rather than needing someone to remember.
    pinned_until    DATE         NULL,
    user_id         BIGINT       NOT NULL,
    organization_id BIGINT       NULL,
    dated           DATETIME     NULL,
    updated         DATETIME     NULL,
    PRIMARY KEY (notice_id),
    -- THE portal read: "published notices for my tenant, newest first". Every portal request runs it.
    KEY idx_notice_published (organization_id, status, published_on),
    -- The ONE_CLASS filter, and the staff list filtered by class.
    KEY idx_notice_grade (organization_id, grade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
