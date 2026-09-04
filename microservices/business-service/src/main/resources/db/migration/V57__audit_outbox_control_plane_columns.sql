-- E4 — bring business-service's audit_outbox up to the shared column set.
--
-- The columns now live on common-audit's AbstractAuditOutbox (a @MappedSuperclass), extracted when auth-service
-- became the second audit producer. Each service still owns its own table and its own migration — schema
-- ownership is per service — so the shared column set arrives here as an ordinary forward migration.
--
-- All nullable, all unused by business-service's eleven trading events today. That is deliberate and not dead
-- weight: `reason` in particular is already being stuffed into `details` by VOID_SALE and REPOSSESSION, and a
-- later slice can move it to its own column without another migration. Nothing is backfilled, because a
-- delivered row is POSTED and its content already reached audit-service.

ALTER TABLE audit_outbox
    ADD COLUMN reason       VARCHAR(255) NULL AFTER details,
    ADD COLUMN before_value VARCHAR(64)  NULL AFTER reason,
    ADD COLUMN after_value  VARCHAR(64)  NULL AFTER before_value,
    ADD COLUMN actor_org_id BIGINT       NULL AFTER after_value,
    ADD COLUMN actor_type   VARCHAR(24)  NULL AFTER actor_org_id,
    ADD COLUMN actor_email  VARCHAR(160) NULL AFTER actor_type;

-- Every row this table has ever held was written by a member of the tenant it belongs to. Stated rather than
-- left NULL so "we do not know" stays distinguishable from "an insider" — the same reasoning as the audit-
-- service backfill in its V2.
UPDATE audit_outbox
   SET actor_org_id = organization_id,
       actor_type   = 'MEMBER'
 WHERE actor_type IS NULL;
