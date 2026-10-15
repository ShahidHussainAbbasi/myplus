-- HMS H3a — who is "the pharmacist" (client ruling L-3, answered 2026-10-10; docs/hms-phase1-design.md §4h).
--
-- Ruling: only the pharmacist may change, dispense or cancel a doctor's prescription. Until now any member who could
-- sell could dispense or cancel (pharma checked WRITE_PRIVILEGE only) — the front desk and the pharmacist were the
-- same person to the system. Market practice (Epic, Cerner, Odoo): a named permission plus built-in role sets.
--
-- Client's choice: NOBODY LOSES ACCESS ON DEPLOY.
--   1. permission rx.dispense — dispense, change or cancel prescriptions;
--   2. added to the built-in Standard and Administrator sets (today's pharmacy staff keep dispensing);
--   3. a new built-in set "Clinic reception" = Standard WITHOUT rx.dispense — the owner moves the front desk onto it;
--   4. the Doctor set (V20) does not get it: a doctor never dispenses.
-- The owner holds every code of the module implicitly (V13), so owners need nothing here.
--
-- Members' tokens carry the new code from their next sign-in / token refresh.
-- Idempotent: every INSERT is guarded, so a re-run changes nothing.

INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT 'rx.dispense', 'rx', 'dispense', 'Dispense, change or cancel prescriptions (pharmacist)', NULL, 410, 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'rx.dispense');

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, 'rx.dispense' FROM permission_set s
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS'
   AND s.name IN ('Standard', 'Administrator')
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = 'rx.dispense');

INSERT INTO permission_set (organization_id, name, description, scope, is_builtin, created_at, module)
SELECT NULL, 'Clinic reception',
       'The clinic''s front desk: registers patients, issues tokens and takes payment. Does not dispense prescriptions or open clinical records.',
       'ALL', 1, NOW(), 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission_set WHERE organization_id IS NULL AND name = 'Clinic reception');

-- Clinic reception = Standard's codes, except rx.dispense (copied, so a later change to Standard is a deliberate
-- decision for each set, never an accident).
INSERT INTO permission_set_item (set_id, permission_code)
SELECT r.id, i.permission_code
  FROM permission_set r
  JOIN permission_set s ON s.organization_id IS NULL AND s.name = 'Standard' AND s.module = 'BUSINESS' AND s.is_builtin = 1
  JOIN permission_set_item i ON i.set_id = s.id
 WHERE r.organization_id IS NULL AND r.name = 'Clinic reception'
   AND i.permission_code <> 'rx.dispense'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item x WHERE x.set_id = r.id AND x.permission_code = i.permission_code);
