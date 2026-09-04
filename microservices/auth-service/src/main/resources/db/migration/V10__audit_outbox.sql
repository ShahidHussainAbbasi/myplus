-- E4 — auth-service's audit outbox: the control plane's transactional record of its own decisions.
--
-- Why the table lives HERE. All five control-plane mutations already execute inside auth-service against this
-- database: entitlement grant/revoke (E1), plan change and status change (E2/E3), business-type change (ONB-1),
-- and the tenant's own capability toggle, which C3c routes to auth by key prefix. So the audit row can be
-- written in the SAME transaction as the change that justifies it — no distributed transaction, no second
-- producer, and no window in which one committed without the other.
--
-- That atomicity is the whole point, and it cuts both ways: a change that is REFUSED (E1's entitlement ceiling
-- throws before the upsert) rolls the audit row back with it. A trail containing changes that never happened
-- is worse than no trail, because nothing downstream can tell which rows are real.
--
-- Delivery to audit-service happens AFTER commit, re-driven by the shared OutboxRelay. A down audit-service
-- therefore never blocks an operator: the row waits as PENDING and the trail catches up.

CREATE TABLE audit_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,

    action           VARCHAR(32)   NOT NULL,   -- ENTITLEMENT_GRANT | PLAN_CHANGE | SHAPE_CHANGE | ...
    entity_type      VARCHAR(32)   NULL,       -- CAPABILITY | ORGANIZATION
    entity_ref       VARCHAR(64)   NULL,       -- capability code, org id, or the shape-history row id
    amount           DECIMAL(19,2) NULL,       -- unused by the control plane; part of the shared column set
    details          VARCHAR(500)  NULL,

    -- WHY. Its own column because it is mandatory on every control-plane write since E2, and because it is the
    -- only question anybody asks of this trail six months later.
    reason           VARCHAR(255)  NULL,

    -- FROM and TO. Both, always: a record that keeps only the new value cannot show a change at all.
    before_value     VARCHAR(64)   NULL,
    after_value      VARCHAR(64)   NULL,

    -- The ACTOR AXIS. organization_id below is the tenant the event is ABOUT; these describe who acted on it.
    -- For a platform operator the two orgs differ, and that is the case the whole column pair exists for.
    actor_org_id     BIGINT        NULL,
    actor_type       VARCHAR(24)   NULL,       -- MEMBER | PLATFORM_OPERATOR | SYSTEM
    actor_email      VARCHAR(160)  NULL,       -- stamped, so the trail outlives the person's user row

    event_key        VARCHAR(64)   NULL,       -- producer-generated UUID; audit-service dedups on it
    occurred_at      DATETIME      NULL,

    status           VARCHAR(20)   NOT NULL,   -- PENDING | POSTED | FAILED
    attempts         INT           NOT NULL,
    last_error       VARCHAR(500)  NULL,

    organization_id  BIGINT        NULL,       -- the SUBJECT tenant; delivery impersonates it
    user_id          BIGINT        NULL,       -- the individual who acted, from the validated token

    created_at       DATETIME      NULL,
    updated_at       DATETIME      NULL,

    PRIMARY KEY (id),
    KEY idx_audit_outbox_pending (status, id)
);
