-- HMS S3a — who may read and write a patient's clinical record. Design: microservices/docs/hms-phase1-design.md §4c.
--
-- ONE code, clinic.consult, in the BUSINESS catalogue (a pharmacy-with-clinic reads module BUSINESS:
-- PermissionService.moduleOf(PHARMA) = BUSINESS). It rides the existing `privileges` JWT claim like every PERM-1 code.
--
-- Who ends up holding it, and why nobody is placed:
--   - the OWNER: an owner holds every code of their module (PermissionService.everything(module)), with no row;
--   - Administrator does NOT: its items were inserted once by V12 from the codes that existed then;
--   - the new built-in set "Doctor" holds ONLY clinic.consult — a doctor does not sell — and is assigned to NOBODY.
--     The owner puts a doctor on it. V12 and V16 both placed people by organisation and swept in a parent and a pupil;
--     this migration places no one.
--
-- Idempotent: each INSERT skips what already exists.

INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT 'clinic.consult', 'clinic', 'consult', 'See and write patients'' clinical records (doctor)', NULL, 400, 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'clinic.consult');

INSERT INTO permission_set (organization_id, name, description, scope, is_builtin, created_at, module)
SELECT NULL, 'Doctor', 'Sees the clinic queue and patients'' clinical records, and records the consultation. Does not sell.',
       'ALL', 1, NOW(), 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission_set WHERE organization_id IS NULL AND name = 'Doctor');

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, 'clinic.consult' FROM permission_set s
 WHERE s.organization_id IS NULL AND s.name = 'Doctor'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = 'clinic.consult');
