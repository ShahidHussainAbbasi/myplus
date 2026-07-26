-- Generic per-tenant configuration store (owner-editable), backing the shared common-settings engine.
-- One row per (org, key), holding only values an owner changed from the code-defined catalog default.
-- Same shape as education/business org_setting — this table lives in the welfare DB (each service owns its data).
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
