-- Multi-location (Stores/Branches) access layer — Pattern A. Central grants of user -> location + role,
-- keyed by a module-qualified location id (the domain store/school id). Inert until P2 populates grants;
-- with no rows, JWT location claims are empty and every service behaves as single-location (unchanged).
CREATE TABLE IF NOT EXISTS user_location_access (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    organization_id   BIGINT       NOT NULL,
    module            VARCHAR(24)  NOT NULL,
    location_id       BIGINT       NOT NULL,
    role_at_location  VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ula_user_module_loc (user_id, module, location_id),
    KEY idx_ula_user_org (user_id, organization_id),
    KEY idx_ula_org_module_loc (organization_id, module, location_id)
) ENGINE=InnoDB;
