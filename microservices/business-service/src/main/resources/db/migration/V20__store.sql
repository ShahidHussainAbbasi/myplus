-- Multi-location (Pattern A): a Store is a physical POS location within a tenant. Transactions (sales,
-- purchases, shifts, returns) get an optional store_id (added in a later migration); master data
-- (catalog, customers, vendors, tax, GL) stays org-wide. A single-store business simply has one row here.
CREATE TABLE IF NOT EXISTS store (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(255) NOT NULL,
    code              VARCHAR(40)  NULL,
    address           VARCHAR(500) NULL,
    phone             VARCHAR(40)  NULL,
    user_id           BIGINT       NULL,        -- creator (audit)
    organization_id   BIGINT       NULL,        -- tenant scope
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME     NULL,
    updated_at        DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_store_org (organization_id)
) ENGINE=InnoDB;
