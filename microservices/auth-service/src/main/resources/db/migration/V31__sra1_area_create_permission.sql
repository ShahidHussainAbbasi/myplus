-- SRA-1 (2026-10-14) — adding a city or an area to the business's address list is a permission, as DATA.
-- Design: microservices/docs/slices/sra-sales-by-rep-and-area.md §4 (business-service V82 holds the lists).
--
-- The customer AND supplier forms both offer "+ New city" / "+ New area" in place, so neither customer.create nor
-- supplier.create alone is the right gate — a member who may only add suppliers must still be able to file one under
-- a new area. Hence its own code, area.create, which the owner can grant or withhold in the matrix like any other.
--
-- NOBODY LOSES ACCESS ON DEPLOY (the V21 rule): until now anyone who could add a customer or a supplier could type any
-- city they liked. So every set — built-in or the owner's own — that holds customer.create or supplier.create gets
-- area.create. The owner holds every code of the module implicitly (V13).
--
-- Reading the lists (/getCities, /getAreas) has no row: the pickers on both forms need it, and an unmapped path is
-- not restricted (V26). Members' tokens carry the new code from their next sign-in / token refresh.
-- Idempotent: every INSERT is guarded. (No dollar-brace anywhere in this file: Flyway reads it as a placeholder.)

-- area.view exists for the MATRIX, not for a gate: the permission screen greys out every action of a row whose
-- `.view` is not ticked (permissions.js — "View is the row's foundation"), so without it an owner could never tick
-- area.create on screen. Reading the lists stays unmapped either way.
INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT 'area.view', 'area', 'view', 'See the city and area list', NULL, 34, 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'area.view');

INSERT INTO permission (code, area, action, label, implies, sort_order, module)
SELECT 'area.create', 'area', 'create', 'Add a city or area to the address list', 'area.view', 35, 'BUSINESS'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'area.create');

INSERT INTO permission_set_item (set_id, permission_code)
SELECT DISTINCT i.set_id, c.code
  FROM permission_set_item i
  JOIN (SELECT 'area.view' AS code UNION ALL SELECT 'area.create') c
 WHERE i.permission_code IN ('customer.create', 'supplier.create')
   AND NOT EXISTS (SELECT 1 FROM permission_set_item x WHERE x.set_id = i.set_id AND x.permission_code = c.code);

-- Most specific first is not at stake here (no other path starts with these), so they sit just after /addVender (230).
INSERT INTO endpoint_permission (http_method, path_prefix, permission_code, sort_order)
SELECT 'POST', '/addCity', 'area.create', 232
 WHERE NOT EXISTS (SELECT 1 FROM endpoint_permission WHERE http_method = 'POST' AND path_prefix = '/addCity');

INSERT INTO endpoint_permission (http_method, path_prefix, permission_code, sort_order)
SELECT 'POST', '/addArea', 'area.create', 234
 WHERE NOT EXISTS (SELECT 1 FROM endpoint_permission WHERE http_method = 'POST' AND path_prefix = '/addArea');
