-- C3c — auth-service becomes the OWNER of tenant capabilities.
--
-- THE DEFECT THIS CLOSES
-- org_setting is per-SERVICE, but a capability is per-TENANT. An owner switching rxRequired off wrote a row
-- into business-service's table; pharma-service read its OWN table, found nothing, applied the catalog default
-- (ON) and never refused. The guard was correct code that could not possibly fire. Storing a tenant fact in N
-- service-local tables gives N answers to one question, and the day they disagree there is no right one.
--
-- WHY AUTH-SERVICE
-- It already owns the tenant. Organization carries type, plan, trialEndsAt and entryCap, and every one of those
-- already reaches the other services as a JWT claim. A capability is the same kind of fact, so it takes the same
-- road: resolved once at login, stamped into the token, read everywhere with no remote call on any hot path.
-- That last point is not a preference — V44 refused a remote check on the sale path precisely because it would
-- fail OPEN exactly when the shop is busiest.
--
-- Same shape as business V26 / marketplace V12 / inventory V7, so the shared engine binds to it unchanged.
-- One row per (org, key), holding ONLY what an owner changed. A tenant with no rows gets the defaults, which is
-- what keeps this deploy inert: every capability still resolves ON.

CREATE TABLE IF NOT EXISTS org_setting (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NULL,
    setting_key      VARCHAR(120) NOT NULL,
    setting_value    VARCHAR(500) NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_org_setting (organization_id, setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
