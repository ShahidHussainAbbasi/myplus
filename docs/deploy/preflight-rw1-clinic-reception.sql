-- ============================================================================================================
-- PRE-FLIGHT RW-1 — who loses what when auth-service V23 runs. READ-ONLY. Run against the auth database
-- (myplusdb_auth) BEFORE deploying, and read every row.          microservices/docs/hms-role-workspaces-design.md
--
-- V23 removes the pharmacy back-office codes from the built-in "Clinic reception" set (client decision D5:
-- move them automatically). Every member on that set loses: buying stock, editing products and prices, suppliers,
-- stock changes, reports, discounts and editing sales. Nobody else loses anything.
--
-- If a row below is a person who REALLY does that work (e.g. the receptionist also receives stock), move them to
-- the right set on Team after the deploy — "Store keeper" for stock, or a custom set — and tell them first.
-- ============================================================================================================

-- 1. the members who are affected, per business
SELECT o.id AS organization_id, o.name AS business, u.email, CONCAT_WS(' ', u.first_name, u.last_name) AS name
  FROM user_permission_set ups
  JOIN permission_set s ON s.id = ups.set_id AND s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Clinic reception'
  JOIN users u ON u.id = ups.user_id
  JOIN memberships m ON m.user_id = u.id AND m.status = 'ACTIVE'
  JOIN organizations o ON o.id = m.organization_id
 ORDER BY o.id, u.email;

-- 2. exactly what they lose (the set's codes that V23 removes)
SELECT i.permission_code AS removed_code
  FROM permission_set_item i
  JOIN permission_set s ON s.id = i.set_id AND s.organization_id IS NULL AND s.is_builtin = 1 AND s.name = 'Clinic reception'
 WHERE i.permission_code IN ('product.create', 'product.edit', 'purchase.create', 'purchase.edit', 'purchase.view',
                             'supplier.create', 'supplier.edit', 'supplier.view', 'stock.create', 'stock.edit',
                             'report.view', 'sale.discount', 'sale.edit')
 ORDER BY 1;
