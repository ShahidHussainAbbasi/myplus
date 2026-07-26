-- P4 cross-module contact view: the denormalized role index. One row per (local record, role) — "party 1001 is
-- CUSTOMER 758 in business AND PATIENT 22 in pharma" — so the view is ONE indexed query instead of a fan-out across
-- four services. Written by the module bridges (piggybacked on the existing upsert) and by the per-module backfill.
-- Holds NO domain data: `label` is a display caption only.

CREATE TABLE party_role_link (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NULL,        -- tenancy; every read is org-scoped (NULL-fallback like party)
    party_id         BIGINT       NOT NULL,    -- -> party.id (by convention; no hard FK, matching party-service style)
    module           VARCHAR(24)  NOT NULL,    -- business | education | welfare | pharma
    role             VARCHAR(20)  NOT NULL,    -- CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT
    local_id         BIGINT       NOT NULL,    -- the module's own primary key
    label            VARCHAR(160) NULL,        -- display caption only — never money/clinical data
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (id),
    -- Makes the write IDEMPOTENT, which the bridge needs: it is retried by design (welfare's edit path re-stamps,
    -- circuit-breaker cooldowns re-fire, the backfill is re-runnable).
    CONSTRAINT uq_role_link UNIQUE (party_id, module, role, local_id),
    KEY idx_role_link_org_party (organization_id, party_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
