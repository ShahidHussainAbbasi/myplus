-- EX-2f / E5 — per-tenant expense settings (the shared common-settings engine), in expense-service's own database.
-- One row per (org, key), holding only the values an owner changed from the catalog default. Same shape as the
-- org_setting tables of business, education and welfare.
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
