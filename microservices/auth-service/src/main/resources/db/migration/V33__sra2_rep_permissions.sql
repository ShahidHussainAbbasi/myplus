-- SRA-2 (2026-10-14) — managing SALES REPS and who covers which outlet is a permission, as DATA.
-- Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §5 (business-service V83 holds the list).
--
-- WHY A CODE and not "owner or admin": the territory screen and its endpoints asked the set's "Sees: All" ROW scope
-- (LocationScope.seesWholeOrg) — a different question. A member whose set had scope ALL could assign territories and
-- was never narrowed to their own outlets; that is the most likely cause of the D6a gate being red since 16 Sep.
--
--   rep.view    see the sales rep list on the territory screen (the matrix row's foundation)
--   rep.manage  add / rename / link / deactivate reps, and assign outlets to them (implies rep.view)
--
-- Granted to the built-in Administrator set — admins assigned territories before (they hold ADMIN_PRIVILEGE, which
-- holds every code anyway), so nobody loses access. The owner holds every code implicitly (V13). A rep's set does
-- NOT get it: the D6a rule is that a rep never assigns outlets to themselves.
-- Idempotent: every INSERT is guarded. (No dollar-brace anywhere in this file: Flyway reads it as a placeholder.)

INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT 'rep.view', 'rep', 'view', 'See the sales reps', NULL, 36, 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'rep.view');

INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT 'rep.manage', 'rep', 'manage', 'Add and change sales reps, and choose who covers each customer', 'rep.view', 37, 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'rep.manage');

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, c.code
  FROM permission_set s
  JOIN (SELECT 'rep.view' AS code UNION ALL SELECT 'rep.manage') c
 WHERE s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Administrator'
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = c.code);

-- The browser's door (PermissionInterceptor). business-service checks rep.manage itself too (a token reaching it
-- directly must be refused as well).
INSERT INTO endpoint_permission (http_method, path_prefix, permission_code, sort_order)
SELECT v.m, v.p, 'rep.manage', v.o FROM (
          SELECT 'POST' AS m, '/addSalesRep' AS p, 236 AS o
UNION ALL SELECT 'POST', '/updateSalesRep', 238
UNION ALL SELECT 'POST', '/assignOutlets', 240
UNION ALL SELECT 'GET',  '/outletAssignments', 242
) v
WHERE NOT EXISTS (SELECT 1 FROM endpoint_permission e WHERE e.http_method = v.m AND e.path_prefix = v.p);

-- What the pages show: the Territory menu entry and the "Usual rep" picker on the customer form.
INSERT INTO ui_menu (module, menu_key, parent_key, label_key, codes, capability, sort_order)
SELECT v.module, v.menu_key, NULL, NULL, v.codes, NULL, v.sort_order FROM (
          SELECT 'BUSINESS' AS module, 'territory' AS menu_key, 'rep.manage' AS codes, 904 AS sort_order
UNION ALL SELECT 'BUSINESS', 'action.rep.assign', 'rep.manage', 905
) v
WHERE NOT EXISTS (SELECT 1 FROM ui_menu m WHERE m.menu_key = v.menu_key);
