-- HMS RW-0a — roles and permissions are DATA, never named in code (client, 2026-10-12; hms-role-workspaces-design.md
-- §8). Until now the code decided by NAME which set a new member gets ("Standard" / "Administrator" / "Teacher" /
-- "Principal"), which set a registered doctor gets ("Doctor"), and which sets and codes the Team screen offers
-- ("Doctor", "Clinic reception", "Pharmacist"; every code starting "clinic."). Those decisions now live on the rows:
--
--   permission.capability       the business setting (Capability code) that must be ON for the code to be offered;
--                               several = any of them, comma-separated (e.g. 'clinic,rxRequired'); NULL = always
--   permission_set.capability   the same, for a set
--   permission_set.default_for  the job a set is the starting point for: MEMBER (a new member), ADMIN (a new admin),
--                               DOCTOR (a registered doctor's login). The owner can point a job at another set later.
--
-- It also removes the "member on no set" fallback that lived in code (AuthService.buildClaims): every business
-- member is placed on their business's default set here, once, and from then on everyone holds exactly what a set
-- in this table says (client decision, 2026-10-12: "members on no set move to the default set").
--
-- And it removes a duplicate made by V23: "Store keeper" repeats the V12 built-in "Storekeeper" (same codes, less
-- report.view). Nobody is on it (checked 2026-10-12); the original stays.
-- Idempotent where it can be: guarded updates and inserts; the ALTERs run once (Flyway).

ALTER TABLE permission ADD COLUMN capability VARCHAR(64) NULL;
ALTER TABLE permission_set ADD COLUMN capability VARCHAR(64) NULL;
ALTER TABLE permission_set ADD COLUMN default_for VARCHAR(16) NULL;

-- 1. which setting a code or set needs (was PermissionVisibility in code) --------------------------------------
UPDATE permission SET capability = 'clinic' WHERE code LIKE 'clinic.%';
UPDATE permission SET capability = 'clinic,rxRequired' WHERE code = 'rx.dispense';

UPDATE permission_set SET capability = 'clinic'
 WHERE organization_id IS NULL AND is_builtin = 1 AND module = 'BUSINESS' AND name IN ('Doctor', 'Clinic reception');
UPDATE permission_set SET capability = 'clinic,rxRequired'
 WHERE organization_id IS NULL AND is_builtin = 1 AND module = 'BUSINESS' AND name = 'Pharmacist';

-- 2. which set each job starts on (was PermissionService.defaultSetFor / AuthService.DOCTOR_SET in code) ----------
UPDATE permission_set SET default_for = 'MEMBER'
 WHERE organization_id IS NULL AND is_builtin = 1 AND ((module = 'BUSINESS' AND name = 'Standard') OR (module = 'EDUCATION' AND name = 'Teacher'));
UPDATE permission_set SET default_for = 'ADMIN'
 WHERE organization_id IS NULL AND is_builtin = 1 AND ((module = 'BUSINESS' AND name = 'Administrator') OR (module = 'EDUCATION' AND name = 'Principal'));
UPDATE permission_set SET default_for = 'DOCTOR'
 WHERE organization_id IS NULL AND is_builtin = 1 AND module = 'BUSINESS' AND name = 'Doctor';

-- 3. every business member is on a set (the code fallback for "no set" is removed) ------------------------------
-- user_permission_set is keyed on user_id (one set per person): a person in several businesses gets one row, the
-- ADMIN default when any of their memberships is ADMIN.
INSERT INTO user_permission_set (user_id, set_id)
SELECT m.user_id,
       CASE WHEN MAX(m.role = 'ADMIN') = 1
            THEN (SELECT s.id FROM permission_set s WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS' AND s.default_for = 'ADMIN' LIMIT 1)
            ELSE (SELECT s.id FROM permission_set s WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS' AND s.default_for = 'MEMBER' LIMIT 1)
       END
  FROM memberships m
  JOIN organizations o ON o.id = m.organization_id AND o.type IN ('BUSINESS', 'PHARMA')
 WHERE m.status = 'ACTIVE' AND m.role IN ('USER', 'ADMIN')
   AND NOT EXISTS (SELECT 1 FROM user_permission_set u WHERE u.user_id = m.user_id)
 GROUP BY m.user_id;

-- 4. the V23 duplicate --------------------------------------------------------------------------------------------
DELETE i FROM permission_set_item i
  JOIN permission_set s ON s.id = i.set_id
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Store keeper'
   AND NOT EXISTS (SELECT 1 FROM user_permission_set u WHERE u.set_id = s.id);
DELETE s FROM permission_set s
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Store keeper'
   AND NOT EXISTS (SELECT 1 FROM user_permission_set u WHERE u.set_id = s.id);
