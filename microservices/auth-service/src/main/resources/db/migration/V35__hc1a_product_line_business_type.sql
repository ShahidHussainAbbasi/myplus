-- HC-1a (2026-10-15) — the PRODUCT LINE and BUSINESS TYPE lists are DATA (standards §1e F3, V6).
-- Design: microservices/docs/slices/hc-1a-healthcare-business-types.md.
--
-- A business type says three things about a new tenant, and before this they were typed into two screens by hand:
--   org_type  → organizations.type: which application it opens (CLINIC → the clinic home, PHARMA → the pharmacy)
--   shape     → org.shape: which features it starts with (common-settings Shape)
--   line_key  → which product line the picker shows it under
-- A new business type later = one row here (+ its modules), not a release of three screens.
--
-- status: ACTIVE = offered by the operator's New tenant form. Education, Welfare and Agriculture are seeded so the
-- list is complete, but INACTIVE: provisioning them from the console has never been tested end to end (the console
-- has only ever created BUSINESS tenants). Switching one ACTIVE is a data change once its gate exists.
--
-- name / description: English fallback only; the screen shows the message key (standard 8e).
-- VARCHAR, never ENUM, for every String field (ddl-auto=validate, standards D-traps). InnoDB stated (D15).
-- Idempotent (D7). No placeholder syntax anywhere in this file (Flyway would substitute it, even in a comment).

CREATE TABLE IF NOT EXISTS product_line (
    line_key    VARCHAR(32)  NOT NULL,
    name_key    VARCHAR(96)  NOT NULL,
    name        VARCHAR(96)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (line_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS business_type (
    type_key         VARCHAR(48)  NOT NULL,
    line_key         VARCHAR(32)  NOT NULL,
    name_key         VARCHAR(96)  NOT NULL,
    name             VARCHAR(96)  NOT NULL,
    description_key  VARCHAR(96)  NULL,
    org_type         VARCHAR(32)  NOT NULL,
    shape            VARCHAR(32)  NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (type_key),
    KEY idx_bt_line (line_key, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO product_line (line_key, name_key, name, sort_order, status) VALUES
    ('BUSINESS',    'ui.js.pl.business',    'Business',    10, 'ACTIVE'),
    ('HEALTHCARE',  'ui.js.pl.healthcare',  'Healthcare',  20, 'ACTIVE'),
    ('EDUCATION',   'ui.js.pl.education',   'Education',   30, 'INACTIVE'),
    ('WELFARE',     'ui.js.pl.welfare',     'Welfare',     40, 'INACTIVE'),
    ('AGRICULTURE', 'ui.js.pl.agriculture', 'Agriculture', 50, 'INACTIVE')
ON DUPLICATE KEY UPDATE line_key = line_key;

-- Business: today's console list, unchanged (org type BUSINESS + the shape). Pharmacy is NOT here any more: a pharmacy
-- is Healthcare → Pharmacy & Pharmaceutical, the live PHARMA type (design point 3).
INSERT INTO business_type (type_key, line_key, name_key, name, description_key, org_type, shape, sort_order, status) VALUES
    ('retail',          'BUSINESS',    'ui.js.bt.retail',         'Retail counter / POS',         NULL, 'BUSINESS',    'retail',       10, 'ACTIVE'),
    ('distribution',    'BUSINESS',    'ui.js.bt.distribution',   'Distribution / wholesale',     NULL, 'BUSINESS',    'distribution', 20, 'ACTIVE'),
    ('storefront',      'BUSINESS',    'ui.js.bt.storefront',     'Online storefront',            NULL, 'BUSINESS',    'storefront',   30, 'ACTIVE'),
    ('general',         'BUSINESS',    'ui.js.bt.general',        'General - show every feature', NULL, 'BUSINESS',    'general',      40, 'ACTIVE'),
    ('hospital_clinic', 'HEALTHCARE',  'ui.js.bt.hospitalClinic', 'Hospital / Clinic',            NULL, 'CLINIC',      'clinic',       10, 'ACTIVE'),
    ('pharmacy',        'HEALTHCARE',  'ui.js.bt.pharmacy',       'Pharmacy & Pharmaceutical',    NULL, 'PHARMA',      'pharmacy',     20, 'ACTIVE'),
    ('school',          'EDUCATION',   'ui.js.bt.school',         'School / college',             NULL, 'EDUCATION',   'general',      10, 'INACTIVE'),
    ('welfare',         'WELFARE',     'ui.js.bt.welfare',        'Welfare / donations',          NULL, 'WELFARE',     'general',      10, 'INACTIVE'),
    ('agriculture',     'AGRICULTURE', 'ui.js.bt.agriculture',    'Farm / agriculture',           NULL, 'AGRICULTURE', 'general',      10, 'INACTIVE')
ON DUPLICATE KEY UPDATE type_key = type_key;
