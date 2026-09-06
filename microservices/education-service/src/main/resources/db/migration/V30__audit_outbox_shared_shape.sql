-- D-7 closed: education's audit outbox adopts the shared column set, so it can record WHO and WHY.
--
-- ── Why this was deferred, and why it is now safe ───────────────────────────────────────────────────
-- E4 extracted common-audit at the second producer and deliberately left education alone: its table was a
-- different shape -- details VARCHAR(1000) against the shared 500, no amount column, and none of the actor
-- columns -- and narrowing a live column is a DATA decision, not a refactor. The debt was recorded rather
-- than quietly taken.
--
-- Measured before deciding, which is what makes it safe now. Across all 88 rows:
--
--     action      max 25   (shared limit 32)      details     max  79   (limit 500)
--     entity_type max 18   (limit 32)             last_error  all NULL  (limit 500)
--     entity_ref  max 35   (limit 64)             status      max   6   (limit 20)
--     event_key   max 36   (limit 64)
--
-- Nothing is close to a limit, so no value is truncated. The 1000-character `details` this service was
-- given has never held more than 79 characters in its life.
--
-- ── What this unlocks ───────────────────────────────────────────────────────────────────────────────
-- EduAuditService could not record a re-drive: D-6's control requires a REASON on every re-send, and this
-- table had nowhere to put one -- nor an actor type to say the platform did it rather than the school. So a
-- re-drive on education worked and was NOT recorded, a hole in the accountability argument D-6 is built on.
--
-- ⚠ ddl-auto=validate: every type below must match AbstractAuditOutbox exactly or education-service will not
-- start at all, and every screen behind it fails for a reason that looks nothing like this file.

ALTER TABLE audit_outbox
    MODIFY COLUMN action      VARCHAR(32)  NOT NULL,
    MODIFY COLUMN entity_type VARCHAR(32)  NULL,
    MODIFY COLUMN entity_ref  VARCHAR(64)  NULL,
    MODIFY COLUMN details     VARCHAR(500) NULL,
    MODIFY COLUMN event_key   VARCHAR(64)  NULL,
    MODIFY COLUMN status      VARCHAR(20)  NOT NULL,
    MODIFY COLUMN last_error  VARCHAR(500) NULL,
    ADD COLUMN amount       DECIMAL(19,2) NULL AFTER entity_ref,
    ADD COLUMN reason       VARCHAR(255)  NULL AFTER details,
    ADD COLUMN before_value VARCHAR(64)   NULL AFTER reason,
    ADD COLUMN after_value  VARCHAR(64)   NULL AFTER before_value,
    ADD COLUMN actor_org_id BIGINT        NULL AFTER after_value,
    ADD COLUMN actor_type   VARCHAR(24)   NULL AFTER actor_org_id,
    ADD COLUMN actor_email  VARCHAR(160)  NULL AFTER actor_type;

-- Every existing row was written by a member of the tenant it belongs to -- 88 marks and fee events, all
-- raised by the school's own staff. Stated rather than left NULL so "we do not know" stays distinguishable
-- from "an insider", exactly as audit-service's V2 and business-service's V57 both do.
UPDATE audit_outbox
   SET actor_org_id = organization_id,
       actor_type   = 'MEMBER'
 WHERE actor_type IS NULL;
