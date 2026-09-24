-- EDU-PERM-1 — a school gets a permission catalogue of its own.
--
-- Design: microservices/docs/slices/edu-perm-1-education-permission-sets.md
--
-- V14 said exactly what this file is for:
--   "The catalog is business-shaped by design (sale, purchase, till, opening balances). Until another
--    module has a catalog of its own, its members hold NO set, and AuthService then mints exactly the
--    role privileges it minted before PERM-1 existed."
-- This is that catalogue for EDUCATION.
--
-- ⚠ WHY `module` EXISTS, on BOTH tables. `catalog()` reads findAllByOrderBySortOrderAsc() and
-- `setsFor()` reads every built-in — neither knows what kind of tenant is asking. Without a module
-- column a school's matrix would offer `sale.create`, and `everything()` would MINT it to an education
-- owner. That is the V12 failure — a ROLE_GUARDIAN holding sale.create — arriving by a different road.
-- The column makes the separation structural instead of remembered.
--
-- ⚠ NOTHING IS DELETED, and the single UPDATE only WIDENS (section 2b: eight shared codes become
-- COMMON, which no module's filter excludes). Existing business rows keep their meaning through the
-- DEFAULT. This migration cannot lose anything.

-- ── 1 · the module axis ─────────────────────────────────────────────────────────────────────────
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='permission' AND COLUMN_NAME='module')=0,
  'ALTER TABLE permission ADD COLUMN module VARCHAR(16) NOT NULL DEFAULT ''BUSINESS''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='permission_set' AND COLUMN_NAME='module')=0,
  'ALTER TABLE permission_set ADD COLUMN module VARCHAR(16) NOT NULL DEFAULT ''BUSINESS''', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2 · the education catalogue — 44 own codes + 8 COMMON, across 19 areas ────────────────────────────────────────────
-- Labels are sentences a headmaster reads, not field names. `implies` is the closure: every write
-- implies the view it is useless without, so a set can never grant "edit a student" while the student
-- list stays invisible.
--
-- Two separations that are the point of the whole slice:
--   marks.enter  IS NOT  reportcard.publish  — a teacher records marks; releasing them to parents is
--                                              the head's act.
--   fee.collect  IS NOT  fee.structure       — taking money at the window is daily work; changing what
--                                              a family is charged is not.
INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT * FROM (
  SELECT 'student.view'        c, 'student'       a, 'view'      x, 'See students'                        l, NULL                                 i, 200 o, 'EDUCATION' m UNION ALL
  SELECT 'student.create',     'student',       'create',    'Admit a student',                     'student.view',                        201, 'EDUCATION' UNION ALL
  SELECT 'student.edit',       'student',       'edit',      'Edit a student record',               'student.view',                        202, 'EDUCATION' UNION ALL
  SELECT 'student.delete',     'student',       'delete',    'Remove a student',                    'student.view',                        203, 'EDUCATION' UNION ALL
  SELECT 'student.promote',    'student',       'promote',   'Promote students to the next class',  'student.view,class.view',             204, 'EDUCATION' UNION ALL

  SELECT 'guardian.view',      'guardian',      'view',      'See guardians',                        NULL,                                 210, 'EDUCATION' UNION ALL
  SELECT 'guardian.create',    'guardian',      'create',    'Add a guardian',                      'guardian.view',                       211, 'EDUCATION' UNION ALL
  SELECT 'guardian.edit',      'guardian',      'edit',      'Edit a guardian',                     'guardian.view',                       212, 'EDUCATION' UNION ALL
  SELECT 'guardian.portal',    'guardian',      'portal',    'Grant a guardian portal access',      'guardian.view,student.view',          213, 'EDUCATION' UNION ALL

  SELECT 'staff.view',         'staff',         'view',      'See staff',                            NULL,                                 220, 'EDUCATION' UNION ALL
  SELECT 'staff.create',       'staff',         'create',    'Add a member of staff',               'staff.view',                          221, 'EDUCATION' UNION ALL
  SELECT 'staff.edit',         'staff',         'edit',      'Edit a member of staff',              'staff.view',                          222, 'EDUCATION' UNION ALL
  SELECT 'staff.delete',       'staff',         'delete',    'Remove a member of staff',            'staff.view',                          223, 'EDUCATION' UNION ALL

  SELECT 'attendance.view',    'attendance',    'view',      'See attendance',                       NULL,                                 230, 'EDUCATION' UNION ALL
  SELECT 'attendance.mark',    'attendance',    'mark',      'Mark student attendance',             'attendance.view,student.view',        231, 'EDUCATION' UNION ALL
  SELECT 'attendance.staff',   'attendance',    'staff',     'Mark staff attendance and leave',     'attendance.view,staff.view',          232, 'EDUCATION' UNION ALL

  SELECT 'class.view',         'class',         'view',      'See classes',                          NULL,                                 240, 'EDUCATION' UNION ALL
  SELECT 'class.edit',         'class',         'edit',      'Set up classes and the academic year','class.view',                          241, 'EDUCATION' UNION ALL

  SELECT 'subject.view',       'subject',       'view',      'See subjects',                         NULL,                                 250, 'EDUCATION' UNION ALL
  SELECT 'subject.edit',       'subject',       'edit',      'Set up subjects',                     'subject.view',                        251, 'EDUCATION' UNION ALL

  SELECT 'timetable.view',     'timetable',     'view',      'See the timetable',                    NULL,                                 260, 'EDUCATION' UNION ALL
  SELECT 'timetable.edit',     'timetable',     'edit',      'Build the timetable',                 'timetable.view,class.view,subject.view', 261, 'EDUCATION' UNION ALL
  SELECT 'timetable.substitute','timetable',    'substitute','Arrange cover for an absent teacher', 'timetable.view,staff.view',           262, 'EDUCATION' UNION ALL

  SELECT 'exam.view',          'exam',          'view',      'See examinations',                     NULL,                                 270, 'EDUCATION' UNION ALL
  SELECT 'exam.edit',          'exam',          'edit',      'Set up examinations and grading',     'exam.view,class.view,subject.view',   271, 'EDUCATION' UNION ALL

  SELECT 'marks.view',         'marks',         'view',      'See marks',                            NULL,                                 280, 'EDUCATION' UNION ALL
  SELECT 'marks.enter',        'marks',         'enter',     'Enter marks',                         'marks.view,exam.view,student.view',   281, 'EDUCATION' UNION ALL

  SELECT 'reportcard.view',    'reportcard',    'view',      'See report cards',                     NULL,                                 290, 'EDUCATION' UNION ALL
  SELECT 'reportcard.generate','reportcard',    'generate',  'Produce report cards',                'reportcard.view,marks.view',          291, 'EDUCATION' UNION ALL
  SELECT 'reportcard.publish', 'reportcard',    'publish',   'Release report cards to parents',     'reportcard.view',                     292, 'EDUCATION' UNION ALL

  SELECT 'homework.view',      'homework',      'view',      'See homework',                         NULL,                                 300, 'EDUCATION' UNION ALL
  SELECT 'homework.set',       'homework',      'set',       'Set homework',                        'homework.view,class.view',            301, 'EDUCATION' UNION ALL

  SELECT 'behaviour.view',     'behaviour',     'view',      'See behaviour records',                NULL,                                 310, 'EDUCATION' UNION ALL
  SELECT 'behaviour.record',   'behaviour',     'record',    'Record behaviour',                    'behaviour.view,student.view',         311, 'EDUCATION' UNION ALL

  SELECT 'communication.view', 'communication', 'view',      'See notices and parents'' evenings',   NULL,                                 320, 'EDUCATION' UNION ALL
  SELECT 'communication.publish','communication','publish',  'Publish a notice or call a meeting',  'communication.view',                  321, 'EDUCATION' UNION ALL

  SELECT 'fee.view',           'fee',           'view',      'See fees',                             NULL,                                 330, 'EDUCATION' UNION ALL
  SELECT 'fee.collect',        'fee',           'collect',   'Collect a fee',                       'fee.view,student.view',               331, 'EDUCATION' UNION ALL
  SELECT 'fee.refund',         'fee',           'refund',    'Refund a fee',                        'fee.view',                            332, 'EDUCATION' UNION ALL
  SELECT 'fee.structure',      'fee',           'structure', 'Change what a family is charged',     'fee.view,class.view',                 333, 'EDUCATION' UNION ALL




  SELECT 'school.view',        'school',        'view',      'See the campus',                       NULL,                                 370, 'EDUCATION' UNION ALL
  SELECT 'school.edit',        'school',        'edit',      'Set up the campus',                   'school.view',                         371, 'EDUCATION' UNION ALL

  SELECT 'transport.view',     'transport',     'view',      'See vehicles and routes',              NULL,                                 380, 'EDUCATION' UNION ALL
  SELECT 'transport.edit',     'transport',     'edit',      'Set up vehicles and routes',          'transport.view',                      381, 'EDUCATION'
) src
WHERE NOT EXISTS (SELECT 1 FROM permission p WHERE p.code = src.c);

-- ── 2b · the codes that belong to EVERY module ──────────────────────────────────────────────────
-- `permission.code` is the PRIMARY KEY, so a code exists once for the whole platform. report.*,
-- settings.* and team.* were seeded as BUSINESS by V12, and they mean exactly the same thing in a
-- school: a report is a report, and adding a user is adding a user. Rather than duplicate them under
-- another name — which would give one concept two authority strings and guarantee the two drift — they
-- become COMMON, and every module's catalogue reads `module IN (<its own>, 'COMMON')`.
--
-- This is the only UPDATE in the file and it WIDENS: a business tenant's catalogue is unchanged because
-- its filter includes COMMON. Nothing is narrowed and no row is removed.
UPDATE permission
   SET module = 'COMMON'
 WHERE code IN ('report.view','report.export','settings.view','settings.edit',
                'team.view','team.create','team.edit','team.delete')
   AND module <> 'COMMON';

-- ── 3 · the five sets a school actually has ──
-- Names follow the mainstream school-information-system taxonomy (PowerSchool, Fedena, Arbor/SIMS)
-- rather than ones invented here: Principal, Class Teacher, Accountant and Front Office are what these
-- people are called in a school, so nobody has to be taught what a set means before using it.───────────────────────────────────────────────────
-- ⚠ NAMES MUST NOT COLLIDE WITH THE BUSINESS BUILT-INS. PermissionService.standardSet() resolves
-- `organization_id IS NULL AND name = 'Standard'` into an Optional — a second row of that name would
-- make it throw. So no education set is called Standard or Administrator.
INSERT INTO permission_set (organization_id, name, description, scope, is_builtin, created_at, module)
SELECT * FROM (
  SELECT NULL oid, 'Teacher'              n, 'Teaches a class. Marks, attendance, homework and behaviour — no fees, no settings.' d, 'ALL' s, 1 b, NOW() c, 'EDUCATION' m UNION ALL
  SELECT NULL, 'Class Teacher',       'A teacher who also keeps their class''s records and writes to parents.',              'ALL', 1, NOW(), 'EDUCATION' UNION ALL
  SELECT NULL, 'Accountant',            'Collects fees and runs the fee reports. Cannot see or enter marks.',                  'ALL', 1, NOW(), 'EDUCATION' UNION ALL
  SELECT NULL, 'Front Office',              'Admissions and records: students, guardians, classes and notices. No money.',         'ALL', 1, NOW(), 'EDUCATION' UNION ALL
  SELECT NULL, 'Principal','Everything in the school, as ADMIN_PRIVILEGE reached before permissions existed.',    'ALL', 1, NOW(), 'EDUCATION'
) src
WHERE NOT EXISTS (
  SELECT 1 FROM permission_set ps
   WHERE ps.organization_id IS NULL AND ps.name = src.n AND ps.module = 'EDUCATION');

-- ── 4 · what is in each set ─────────────────────────────────────────────────────────────────────
-- ⚠ INSERTED CLOSED. `effectiveFor` reads permission_set_item RAW — the closure is computed in
-- PermissionService.save(), which a migration does not go through. So the implied codes are added
-- explicitly in 4b; without that, "Teacher" would grant marks.enter while the marks screen refused to
-- open, which is exactly the half-granted state `implies` exists to prevent.
INSERT INTO permission_set_item (set_id, permission_code)
SELECT ps.id, src.code FROM (
  SELECT 'Teacher' sname, 'marks.enter' code UNION ALL
  SELECT 'Teacher', 'attendance.mark'   UNION ALL
  SELECT 'Teacher', 'homework.set'      UNION ALL
  SELECT 'Teacher', 'behaviour.record'  UNION ALL
  SELECT 'Teacher', 'timetable.view'    UNION ALL
  SELECT 'Teacher', 'reportcard.view'   UNION ALL
  SELECT 'Teacher', 'communication.view' UNION ALL

  SELECT 'Class Teacher', 'marks.enter'          UNION ALL
  SELECT 'Class Teacher', 'attendance.mark'      UNION ALL
  SELECT 'Class Teacher', 'homework.set'         UNION ALL
  SELECT 'Class Teacher', 'behaviour.record'     UNION ALL
  SELECT 'Class Teacher', 'timetable.view'       UNION ALL
  SELECT 'Class Teacher', 'student.edit'         UNION ALL
  SELECT 'Class Teacher', 'guardian.view'        UNION ALL
  SELECT 'Class Teacher', 'reportcard.generate'  UNION ALL
  SELECT 'Class Teacher', 'communication.publish' UNION ALL

  SELECT 'Accountant', 'fee.collect'    UNION ALL
  SELECT 'Accountant', 'fee.refund'     UNION ALL
  SELECT 'Accountant', 'fee.structure'  UNION ALL
  SELECT 'Accountant', 'report.export'  UNION ALL
  SELECT 'Accountant', 'guardian.view'  UNION ALL

  SELECT 'Front Office', 'student.create'        UNION ALL
  SELECT 'Front Office', 'student.edit'          UNION ALL
  SELECT 'Front Office', 'student.promote'       UNION ALL
  SELECT 'Front Office', 'guardian.create'       UNION ALL
  SELECT 'Front Office', 'guardian.edit'         UNION ALL
  SELECT 'Front Office', 'guardian.portal'       UNION ALL
  SELECT 'Front Office', 'staff.view'            UNION ALL
  SELECT 'Front Office', 'class.edit'            UNION ALL
  SELECT 'Front Office', 'subject.edit'          UNION ALL
  SELECT 'Front Office', 'communication.publish' UNION ALL
  SELECT 'Front Office', 'report.view'           UNION ALL
  SELECT 'Front Office', 'school.view'      UNION ALL
  SELECT 'Front Office', 'transport.edit'
) src
JOIN permission_set ps
  ON ps.organization_id IS NULL AND ps.name = src.sname AND ps.module = 'EDUCATION'
WHERE NOT EXISTS (
  SELECT 1 FROM permission_set_item i WHERE i.set_id = ps.id AND i.permission_code = src.code);

-- The Principal holds the whole EDUCATION catalogue, plus the COMMON codes.
INSERT INTO permission_set_item (set_id, permission_code)
SELECT ps.id, p.code
  FROM permission_set ps
  JOIN permission p ON p.module IN ('EDUCATION', 'COMMON')
 WHERE ps.organization_id IS NULL AND ps.name = 'Principal' AND ps.module = 'EDUCATION'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = ps.id AND i.permission_code = p.code);

-- 4b · CLOSE every education built-in: add each code implied by one it already holds.
-- One pass is enough because every `implies` target in this catalogue is a leaf `.view` whose own
-- implies is NULL. A future code implying a non-leaf must extend this.
INSERT INTO permission_set_item (set_id, permission_code)
-- ⚠ DISTINCT is load-bearing. Two codes in one set can imply the SAME view — Front Office holds both
-- student.promote and class.edit, and both imply class.view — so without it this SELECT returns that
-- pair twice and the insert dies on the primary key. `NOT EXISTS` cannot catch it: it tests the table
-- as it was BEFORE the statement, not the rows the statement is itself producing.
SELECT DISTINCT i.set_id, p2.code
  FROM permission_set_item i
  JOIN permission_set ps ON ps.id = i.set_id AND ps.module = 'EDUCATION'
  JOIN permission p  ON p.code = i.permission_code AND p.implies IS NOT NULL
  JOIN permission p2 ON FIND_IN_SET(p2.code, p.implies) > 0
 WHERE NOT EXISTS (
   SELECT 1 FROM permission_set_item x WHERE x.set_id = i.set_id AND x.permission_code = p2.code);

-- ── 5 · place everybody already here, so nothing changes on the morning this deploys ────────────
-- The same shape as V15 (pharma) and the same exclusions: scoped to EDUCATION organizations only —
-- the V12 lesson — and owners are never placed, because their access is implicit (V13).
INSERT INTO user_permission_set (user_id, set_id)
SELECT u.id, (SELECT id FROM permission_set
               WHERE organization_id IS NULL AND name = 'Principal' AND module = 'EDUCATION')
  FROM users u
  JOIN memberships m   ON m.user_id = u.id
  JOIN organizations o ON o.id = m.organization_id AND o.type = 'EDUCATION'
  JOIN users_roles ur  ON ur.user_id = u.id
  JOIN roles r         ON r.id = ur.role_id
  JOIN roles_privileges rp ON rp.role_id = r.id
  JOIN privileges pv   ON pv.id = rp.privilege_id AND pv.name = 'ADMIN_PRIVILEGE'
 WHERE u.id NOT IN (SELECT user_id FROM user_permission_set)
   AND u.id NOT IN (SELECT ur2.user_id FROM users_roles ur2
                      JOIN roles r2 ON r2.id = ur2.role_id
                     WHERE r2.name = 'ROLE_OWNER')
 GROUP BY u.id;

-- Every other EDUCATION member -> Teacher. That set deliberately holds the nine screens which carry no
-- guard at all today (Marks Entry, Report Cards, Timetable, Substitution, Leave, Homework, Parents'
-- evenings, Notices, Behaviour), so a teacher signing in the next morning loses nothing.
INSERT INTO user_permission_set (user_id, set_id)
SELECT u.id, (SELECT id FROM permission_set
               WHERE organization_id IS NULL AND name = 'Teacher' AND module = 'EDUCATION')
  FROM users u
  JOIN memberships m   ON m.user_id = u.id
  JOIN organizations o ON o.id = m.organization_id AND o.type = 'EDUCATION'
 WHERE u.id NOT IN (SELECT user_id FROM user_permission_set)
   AND u.id NOT IN (SELECT ur2.user_id FROM users_roles ur2
                      JOIN roles r2 ON r2.id = ur2.role_id
                     WHERE r2.name = 'ROLE_OWNER')
 GROUP BY u.id;
