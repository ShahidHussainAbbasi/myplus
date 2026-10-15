-- SRA-1 (2026-10-14) — who sees "+ New city" / "+ New area" on the customer and supplier forms: an in-page ACTION row,
-- exactly like action.product.add (V25) behind the purchase form's "+ New product". The page names only the key
-- (th:if="@perm.menu('action.area.add')"); which code unlocks it is this row. The server's refusal (V31's
-- endpoint_permission rows for /addCity and /addArea) stays the control — this only stops offering a button that
-- would be refused.
-- (No dollar-brace anywhere in this file: Flyway would read it as a placeholder, even in a comment.)

INSERT INTO ui_menu (module, menu_key, parent_key, label_key, codes, capability, sort_order)
SELECT 'BUSINESS', 'action.area.add', NULL, NULL, 'area.create', NULL, 903
 WHERE NOT EXISTS (SELECT 1 FROM ui_menu WHERE menu_key = 'action.area.add');
