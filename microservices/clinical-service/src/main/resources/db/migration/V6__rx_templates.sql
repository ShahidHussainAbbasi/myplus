-- HMS S3b-2 — prescription templates (docs/hms-phase1-design.md §4e). Additive only.
--
-- A template is the clinic's (shared by its doctors). name_key = LOWER(TRIM(name)) carries the per-clinic unique
-- name; a retired template frees its name (name_key is NULL once retired, and NULLs never collide). Never deleted.
CREATE TABLE rx_template (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    name             VARCHAR(80)   NOT NULL,
    name_key         VARCHAR(80)   NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_by       BIGINT        NULL,
    created_at       DATETIME      NULL,
    retired_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_rx_template_name (organization_id, name_key),
    KEY idx_rx_template_org (organization_id, status, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE rx_template_item (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT        NOT NULL,
    template_id      BIGINT        NOT NULL,
    line_no          INT           NOT NULL,
    product_id       BIGINT        NOT NULL,
    medicine_name    VARCHAR(200)  NOT NULL,
    quantity         INT           NOT NULL,
    dosage           VARCHAR(100)  NULL,
    frequency        VARCHAR(100)  NULL,
    duration         VARCHAR(100)  NULL,
    PRIMARY KEY (id),
    KEY idx_rx_template_item (organization_id, template_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
