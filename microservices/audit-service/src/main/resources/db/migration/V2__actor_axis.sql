-- E4 — the ACTOR AXIS.
--
-- Until now every row in audit_event was written by a member of the tenant it belongs to, so `user_id` plus
-- `organization_id` said everything there was to say about who acted. E4 emits control-plane events, where a
-- platform operator acts ON a tenant they are not part of — and the table has one slot for a user and one for
-- an org, so one of the two facts would be lost.
--
-- Whichever slot were reused, the trail would be wrong in a way that cannot be repaired: audit_event is
-- append-only by design and by constraint. Filed under the operator, the customer never sees what was done to
-- them; filed under the tenant with no actor axis, the customer sees a user id that is not one of their staff
-- and cannot tell a platform decision from a colleague's. The second is the dangerous one — a trail that
-- misattributes is believed.
--
-- actor_type is deliberately MEMBER | PLATFORM_OPERATOR | SYSTEM and NOT a role name. audit-service does not
-- know roles and never will; encoding one would make the column lie the instant that person's role changed,
-- and a role is already answerable from user_id. The axis the trail needs is inside-or-outside this tenant,
-- which is a fact about the event and does not decay.

ALTER TABLE audit_event
    ADD COLUMN actor_org_id BIGINT       NULL AFTER user_id,
    ADD COLUMN actor_type   VARCHAR(24)  NULL AFTER actor_org_id,
    -- The actor's email, STAMPED rather than resolved on read: a trail must stay readable after the person
    -- has left and their user row is gone. Same rule CurrentUser.email()'s javadoc records for exactly this.
    ADD COLUMN actor_email  VARCHAR(160) NULL AFTER actor_type,
    -- `reason` gets its own column rather than sharing `details`. It is mandatory on every control-plane
    -- write as of E2, it is the only question anybody asks of this trail six months later, and `details` is
    -- shared free text that AuditIngestService truncates at 500 characters without complaint.
    ADD COLUMN reason       VARCHAR(255) NULL AFTER details,
    -- Two typed scalars, not a JSON blob. Four of the five control-plane events change one scalar; the fifth
    -- (business type) changes a set whose full contents org_shape_history already holds. A record of a change
    -- that keeps only the new value cannot show a change at all.
    ADD COLUMN before_value VARCHAR(64)  NULL AFTER reason,
    ADD COLUMN after_value  VARCHAR(64)  NULL AFTER before_value;

-- Backfill: true of every row written to date, and it is precisely the assumption E4 stops being able to
-- make. Stated as a migration rather than left NULL so "we do not know" is distinguishable from "an insider".
UPDATE audit_event
   SET actor_org_id = organization_id,
       actor_type   = 'MEMBER'
 WHERE actor_type IS NULL;

-- The Activity panel filters by event family; the trading trail is already served by (organization_id, id).
CREATE INDEX idx_audit_org_action_id ON audit_event (organization_id, action, id);
