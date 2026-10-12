-- HMS RW-1 — role workspaces: the clinic's own permissions, a real front desk, a pharmacist and a store keeper
-- (client, 2026-10-12; microservices/docs/hms-role-workspaces-design.md §3.2, decisions D1–D5).
--
-- Until now the clinic had ONE permission (clinic.consult). Patients, tokens, the queue and a doctor's day were open to
-- every member once the clinic was on, and "Clinic reception" was a copy of Standard without rx.dispense — a pharmacy
-- clerk who could buy stock, edit prices and read the reports.
--
-- 1. eight clinic codes (module BUSINESS, area clinic);
-- 2. Administrator gets all of them; Standard gets the front-desk ones (nobody on Standard loses anything);
--    Doctor gets the two read codes;
-- 3. "Clinic reception" = the front desk (D1: takes the consultation fee through the till, D2: sets a doctor's day):
--    the pharmacy back-office codes are REMOVED (D5: moved automatically — run
--    docs/deploy/preflight-rw1-clinic-reception.sql first to see who is affected);
-- 4. new built-in "Pharmacist" (D3: dispenses and sells, does NOT receive stock) and "Store keeper" (purchases,
--    suppliers, products, stock);
-- 5. every business custom set that existed before this migration gets the front-desk codes — exactly yesterday's
--    behaviour, when any member could do those things (the V22 pattern). Members on NO set: AuthService.buildClaims.
-- The owner holds every code implicitly (V13). Members' tokens carry the change from their next sign-in / refresh.
-- Idempotent: every INSERT is guarded; the DELETE removes only what must not be there.

-- 1. the codes ------------------------------------------------------------------------------------------------------
INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT v.code, 'clinic', v.action, v.label, v.implies, v.sort_order, 'BUSINESS'
  FROM (
    SELECT 'clinic.patient.view'  AS code, 'patient.view'  AS action, 'Find a patient'                                     AS label, NULL                  AS implies, 401 AS sort_order
    UNION ALL SELECT 'clinic.patient.edit',  'patient.edit',  'Register or correct a patient',                             'clinic.patient.view',  402
    UNION ALL SELECT 'clinic.queue.view',    'queue.view',    'See today''s queue',                                        NULL,                   403
    UNION ALL SELECT 'clinic.token.issue',   'token.issue',   'Give a patient a token, or move it to another doctor',      'clinic.queue.view',    404
    UNION ALL SELECT 'clinic.token.cancel',  'token.cancel',  'Cancel a token (with a reason)',                            'clinic.queue.view',    405
    UNION ALL SELECT 'clinic.doctor.day',    'doctor.day',    'Set a doctor''s day: patients a day, not today',            NULL,                   406
    UNION ALL SELECT 'clinic.doctor.manage', 'doctor.manage', 'Add doctors, link their login, route to a pharmacy branch', NULL,                   407
    UNION ALL SELECT 'clinic.settings',      'settings',      'Change the clinic''s settings',                             NULL,                   408
  ) v
 WHERE NOT EXISTS (SELECT 1 FROM permission p WHERE p.code = v.code);

-- 2. Administrator: everything · Standard: the front desk · Doctor: read ---------------------------------------------
INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s JOIN permission p ON p.code LIKE 'clinic.%' AND p.code <> 'clinic.consult'
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS' AND s.name = 'Administrator'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = p.code);

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s
  JOIN permission p ON p.code IN ('clinic.patient.view', 'clinic.patient.edit', 'clinic.queue.view',
                                  'clinic.token.issue', 'clinic.token.cancel', 'clinic.doctor.day')
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS' AND s.name IN ('Standard', 'Clinic reception')
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = p.code);

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s JOIN permission p ON p.code IN ('clinic.patient.view', 'clinic.queue.view')
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS' AND s.name = 'Doctor'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = p.code);

-- 3. the front desk is not the pharmacy's back office (D1: keeps the till and selling to take the fee) ----------------
DELETE i FROM permission_set_item i
  JOIN permission_set s ON s.id = i.set_id
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.module = 'BUSINESS' AND s.name = 'Clinic reception'
   AND i.permission_code IN ('product.create', 'product.edit', 'purchase.create', 'purchase.edit', 'purchase.view',
                             'supplier.create', 'supplier.edit', 'supplier.view', 'stock.create', 'stock.edit',
                             'report.view', 'sale.discount', 'sale.edit');

UPDATE permission_set
   SET description = 'The clinic''s front desk: registers patients, gives tokens, keeps today''s queue and the doctors'' day, and takes the consultation fee at the till. No stock, purchases, prices or reports.'
 WHERE organization_id IS NULL AND is_builtin = 1 AND module = 'BUSINESS' AND name = 'Clinic reception';

-- 4. Pharmacist and Store keeper ----------------------------------------------------------------------------------
INSERT INTO permission_set (organization_id, name, description, scope, is_builtin, created_at, module)
SELECT NULL, 'Pharmacist',
       'Fills and dispenses prescriptions and sells at the till. Does not receive stock or change prices (that is the Store keeper or the administrator).',
       'ALL', 1, NOW(), 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission_set WHERE organization_id IS NULL AND name = 'Pharmacist');

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s
  JOIN permission p ON p.code IN ('customer.create', 'customer.edit', 'customer.view', 'product.view', 'stock.view',
                                  'sale.create', 'sale.discount', 'sale.edit', 'sale.view',
                                  'till.create', 'till.view', 'till.close', 'rx.dispense', 'clinic.patient.view')
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Pharmacist'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = p.code);

INSERT INTO permission_set (organization_id, name, description, scope, is_builtin, created_at, module)
SELECT NULL, 'Store keeper',
       'Receives stock: purchases, suppliers, products and stock counts. Does not sell or dispense.',
       'ALL', 1, NOW(), 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission_set WHERE organization_id IS NULL AND name = 'Store keeper');

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s
  JOIN permission p ON p.code IN ('product.create', 'product.edit', 'product.view', 'purchase.create', 'purchase.edit',
                                  'purchase.view', 'supplier.create', 'supplier.edit', 'supplier.view',
                                  'stock.create', 'stock.edit', 'stock.view')
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Store keeper'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = p.code);

-- 5. custom sets that existed before this migration keep what their members could do yesterday -------------------
INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s
  JOIN permission p ON p.code IN ('clinic.patient.view', 'clinic.patient.edit', 'clinic.queue.view',
                                  'clinic.token.issue', 'clinic.token.cancel', 'clinic.doctor.day')
 WHERE s.organization_id IS NOT NULL AND s.is_builtin = 0 AND s.module = 'BUSINESS'
   AND (s.created_at IS NULL OR s.created_at < NOW())
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = p.code);
