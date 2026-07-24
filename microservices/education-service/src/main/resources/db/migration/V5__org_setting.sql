-- Generic per-tenant configuration store (owner-editable). One row per (org, key), holding only the values
-- an owner has changed away from the code-defined catalog default. New configurable policies register in the
-- catalog + read from here — NO new column per policy. See SettingsCatalog / SettingsService.
CREATE TABLE org_setting (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NULL,
    setting_key      VARCHAR(100) NOT NULL,
    setting_value    VARCHAR(500) NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_org_setting (organization_id, setting_key)
) ENGINE=InnoDB;
