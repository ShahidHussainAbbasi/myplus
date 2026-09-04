-- ONB-3 — what a business-type change destroyed, so it can be put back.
--
-- THE PROBLEM THIS CLOSES
-- Changing a tenant's business type RE-APPLIES the new shape's preset, which CLEARS every org.cap.* override
-- the owner had set (ONB-1, the owner's ruling). Switching back restores capabilities — the shape is just a
-- settings row and nothing is deleted — but it applies the OTHER shape's preset, not the switches the owner
-- personally chose. Those were gone, and gone SILENTLY: nothing recorded what they were, so nobody could even
-- say what had been lost.
--
-- That was the one irreversible part of a business-type change, and the only part nothing showed anyone.
--
-- WHY A TABLE AND NOT THE AUDIT LOG
-- The audit slice (E4) does not exist yet, and this is not only an audit record. An audit answers "what
-- happened"; this has to answer "what to put back". When E4 lands it reads this rather than replacing it.
--
-- WHY previous_overrides IS JSON, against this codebase's usual habit
-- The payload is "the key/value rows that existed at one instant" — never queried by key, never joined, never
-- aggregated. It is a snapshot, which is the one shape JSON is genuinely right for. SAAS-BUILD-STANDARDS §4a
-- rejected JSON for entitlement STATUS, DATES and SOURCE — facts that must be queryable. This is not those.
--
-- previous_shape IS NULLABLE on purpose: a tenant that had never chosen a business type is exactly the case
-- this platform has 37 of, and "there was nothing before" is a fact worth keeping rather than a gap to avoid.

CREATE TABLE IF NOT EXISTS org_shape_history (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT       NOT NULL,
    changed_at          DATETIME     NOT NULL,
    changed_by          BIGINT       NULL,
    previous_shape      VARCHAR(40)  NULL,
    new_shape           VARCHAR(40)  NOT NULL,
    previous_overrides  TEXT         NULL,
    reason              VARCHAR(255) NULL,
    PRIMARY KEY (id),
    -- The only read this table has: one tenant's history, newest first. The index serves it and nothing else,
    -- deliberately — a second index here would be speculation about a screen nobody has asked for.
    KEY ix_org_shape_history_org (organization_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
