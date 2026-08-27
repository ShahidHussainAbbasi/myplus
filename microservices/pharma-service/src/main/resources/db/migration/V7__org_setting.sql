-- C3b — per-tenant configuration store for pharma-service, backing the shared common-settings engine.
--
-- WHY pharma-service NEEDED THIS BEFORE IT COULD BE GUARDED
-- Capabilities are settings under the reserved org.cap.* namespace, and the settings engine is
-- @ConditionalOnBean(SettingsStore.class). pharma-service had no store, so it had no SettingsService and
-- therefore no CapabilityService to inject. Prescriptions — the most obviously capability-gated write in the
-- product — could not be refused server-side at all. This table is what makes that possible.
--
-- One row per (org, key), holding ONLY what an owner changed from the code-defined catalog default. An org
-- that never opens the Configuration screen has no rows here and gets the defaults, which is what keeps the
-- rollout inert: every capability resolves ON exactly as it did before.
--
-- Its own table rather than a read of business-service's: each service owns its data, so pharma does not reach
-- into another service's schema to answer a question about its own dispensing policy. Same shape as
-- inventory's V7, marketplace's V12 and business-service's V26.

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
