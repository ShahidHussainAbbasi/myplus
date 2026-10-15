-- HC-1a (2026-10-15) — the workspace tiles (wsTiles) belong to BUSINESS TYPES. The Hospital / Clinic landing is the same
-- tile page, WITHOUT the Pharmacy and Stock tiles (the user, 2026-10-15). Design: slices/hc-1a-healthcare-business-types.md.
--
-- ui_workspace rows were filtered by feature (capability) and permission codes only, so a clinic tenant would also have
-- been offered "Pharmacy" and "Stock" — both open the shop dashboard, which a clinic does not have.
--
--   frontdesk · doctor · admin  → CLINIC, and PHARMA while a tenant still runs clinic + pharmacy combined (HC-1e
--                                 splits those; nobody loses a screen before then)
--   pharmacy · store ("Stock")  → PHARMA only — never a clinic
--
-- Empty org_types = every type (the rule for any future tile that is not type-specific).
-- Idempotent (D7). No placeholder syntax anywhere in this file (Flyway would substitute it, even in a comment).

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_workspace' AND COLUMN_NAME = 'org_types') = 0,
    'ALTER TABLE ui_workspace ADD COLUMN org_types VARCHAR(160) NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE ui_workspace SET org_types = 'CLINIC,PHARMA' WHERE ws_key IN ('frontdesk', 'doctor', 'admin');
UPDATE ui_workspace SET org_types = 'PHARMA'        WHERE ws_key IN ('pharmacy', 'store');
