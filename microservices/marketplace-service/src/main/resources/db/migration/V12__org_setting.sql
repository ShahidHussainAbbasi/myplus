-- OMS O3 — per-tenant configuration store for marketplace, backing the shared common-settings engine.
--
-- One row per (org, key), holding ONLY the values an owner changed from the code-defined catalog default. An org
-- that never opens the Configuration screen has no rows here and gets the defaults, which reproduce exactly the
-- literals that used to live in ShippingOption — so this migration changes no behaviour by itself.
--
-- Same shape as business-service's V26 and education's, and deliberately a table of its own: each service owns
-- its data, so marketplace does not reach into another service's schema to read its own policy.

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
