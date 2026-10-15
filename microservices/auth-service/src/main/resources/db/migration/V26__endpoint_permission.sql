-- HMS RW-0c — the monolith's endpoint rules are DATA (client, 2026-10-12: "nothing should be hard coded … populated
-- from the databases"; hms-role-workspaces-design.md section 8). Which permission a request needs used to be a list
-- in PermissionInterceptor (23 rules) with a hand-written sentence per code; both now come from here: the rule from
-- this table, the sentence from permission.label.
--
--   http_method   GET / POST / DELETE … ; '*' = any
--   path_prefix   matched as a PREFIX of the request path
--   permission_code  the code the member must hold (the owner and the platform roles hold all)
--   sort_order    MOST SPECIFIC FIRST — the first match wins. Load-bearing: /addProductStock must be tested before
--                 /addProduct (a prefix of it), or adding stock would ask for "add a product".
--
-- A path with no row is not restricted by this table (unchanged from PERM-1: an incomplete map must not refuse
-- screens that worked yesterday). Seeded with exactly the 23 rules of PermissionInterceptor, in its order.
-- (No dollar-brace anywhere in this file: Flyway would read it as a placeholder, even in a comment.)

CREATE TABLE IF NOT EXISTS endpoint_permission (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    http_method      VARCHAR(8)  NOT NULL,
    path_prefix      VARCHAR(128) NOT NULL,
    permission_code  VARCHAR(64) NOT NULL,
    sort_order       INT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_endpoint_permission (http_method, path_prefix)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO endpoint_permission (http_method, path_prefix, permission_code, sort_order)
SELECT v.m, v.p, v.c, v.o FROM (
          SELECT 'POST' AS m, '/voidSell' AS p, 'sale.void' AS c, 10 AS o
UNION ALL SELECT 'POST',   '/deleteSell',            'sale.delete',      20
UNION ALL SELECT 'POST',   '/deletePurchase',        'purchase.delete',  30
UNION ALL SELECT 'POST',   '/deleteCustomer',        'customer.delete',  40
UNION ALL SELECT 'POST',   '/deleteProduct',         'product.delete',   50
UNION ALL SELECT 'POST',   '/deleteVender',          'supplier.delete',  60
UNION ALL SELECT 'POST',   '/team/permissions',      'team.edit',        70
UNION ALL SELECT 'DELETE', '/team/permissions',      'team.edit',        80
UNION ALL SELECT 'POST',   '/team/users',            'team.create',      90
UNION ALL SELECT 'GET',    '/team/',                 'team.view',        100
UNION ALL SELECT 'POST',   '/reverseOpeningBalance', 'opening.reverse',  110
UNION ALL SELECT 'POST',   '/postOpeningBalance',    'opening.create',   120
UNION ALL SELECT 'GET',    '/openingBalanceSummary', 'opening.view',     130
UNION ALL SELECT 'POST',   '/saveBusinessConfig',    'settings.edit',    140
UNION ALL SELECT 'POST',   '/updateSell',            'sale.edit',        150
UNION ALL SELECT 'POST',   '/addSell',               'sale.create',      160
UNION ALL SELECT 'POST',   '/batchPricePreview',     'sale.create',      170
UNION ALL SELECT 'POST',   '/updatePurchase',        'purchase.edit',    180
UNION ALL SELECT 'POST',   '/addPurchase',           'purchase.create',  190
UNION ALL SELECT 'POST',   '/addProductStock',       'stock.create',     200
UNION ALL SELECT 'POST',   '/addCustomer',           'customer.create',  210
UNION ALL SELECT 'POST',   '/addProduct',            'product.create',   220
UNION ALL SELECT 'POST',   '/addVender',             'supplier.create',  230
) v
WHERE NOT EXISTS (SELECT 1 FROM endpoint_permission e WHERE e.http_method = v.m AND e.path_prefix = v.p);
