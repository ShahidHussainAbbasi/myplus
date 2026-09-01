-- E1 — the ENTITLEMENT CEILING.
--
-- THE DEFECT THIS CLOSES
-- `SettingsService.set` validated that a key was in the catalog and nothing else, and `org.cap.*` keys ARE in
-- the catalog — that is how the Configuration screen renders them. `SettingsController.save` gates on
-- ROLE_OWNER or ADMIN_PRIVILEGE, both of which every tenant owner holds inside their own org. So any owner
-- could POST org.cap.installments=true and hold a paid capability, permanently, at no charge. Org scoping was
-- never breached: this is a LICENSING hole, and it was the one layer of the four-layer control model that did
-- not exist.
--
-- WHY AUTH-SERVICE (ruling D-2)
-- Same reason capabilities themselves landed here in C3c: auth already owns the tenant. `organizations`
-- carries type, plan, trial_ends_at and entry_cap, and every one of those already reaches the other services
-- as a JWT claim. An entitlement is the same kind of fact and takes the same road — resolved once at token
-- mint, folded into the `caps` claim, read everywhere with NO remote call on any hot path.
--
-- WHAT THIS TABLE IS, AND IS NOT
-- It is the per-tenant DEVIATION from the plan. The plan's own contents live in code (`Plan`, ruling D-3),
-- like Shape.preset(), because they change with a release and benefit from being greppable. This table holds
-- what a contract, a trial extension or an operator decided for ONE customer.
--
-- It is NOT a second copy of org_setting. org_setting is what the OWNER switched on; this is what the PLATFORM
-- sold. Effective = entitled AND enabled. Merging them would put the tenant back in charge of its own ceiling.
--
-- WHY VARCHAR AND NOT A MySQL ENUM
-- Against the platform's usual habit, deliberately. A new Capability value must not need an
-- `ALTER … MODIFY enum` on a licensing table before it can be sold — that cost is recorded in
-- project_enum_string_mysql_enum_migration. An unrecognised code here resolves to "no row", which is a
-- well-defined answer, rather than to a broken read.
--
-- THE UNIQUE KEY IS THE INDEX
-- (organization_id, capability) serves the only lookup this table has and enforces one row per pair at the
-- same time. No second index is added, deliberately.
--
-- THIS MIGRATION IS EMPTY OF DATA ON PURPOSE
-- Grandfathering is done by EntitlementSeeder in Java, not here: the set a tenant currently has EFFECTIVELY
-- enabled depends on shape presets and overrides that only CapabilityService knows how to combine. Expressing
-- that in SQL would be a second implementation of the resolution order, and the day the two disagree there is
-- no right one.

CREATE TABLE IF NOT EXISTS org_entitlement (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    capability       VARCHAR(60)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    source           VARCHAR(20)  NOT NULL,
    starts_at        DATETIME     NULL,
    ends_at          DATETIME     NULL,
    reason           VARCHAR(255) NULL,
    granted_by       BIGINT       NULL,
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_org_entitlement (organization_id, capability)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
