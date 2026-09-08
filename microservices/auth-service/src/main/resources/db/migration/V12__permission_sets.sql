-- PERM-1 — permission sets: catalog, sets, assignment, and the migration of everybody already here.
--
-- Design: microservices/docs/slices/perm-1-permission-sets-design.md
--
-- ⚠ THE MOST IMPORTANT THING IN THIS FILE IS THE LAST STATEMENT.
-- Every existing member is placed on a built-in set that reproduces EXACTLY what their current role
-- already reaches. On the morning this deploys, nobody's screen changes and nobody loses access
-- mid-trading. New sets are the owner's to create afterwards. A permissions feature that silently
-- locks a shop's staff out on deploy is worse than no permissions feature.

-- ── 1 · the catalog ────────────────────────────────────────────────────────────────────────────
-- `implies` is the CLOSURE: granting this permission also grants these, computed on save. It is data,
-- not code, because the dependency between "create a sale" and "see the product list" is a fact about
-- the screens, and a fact about screens belongs beside them - not buried in a service.
CREATE TABLE permission (
    code        VARCHAR(64)  NOT NULL PRIMARY KEY,
    area        VARCHAR(32)  NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    label       VARCHAR(128) NOT NULL,
    implies     VARCHAR(512) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uq_permission_area_action (area, action)
) ENGINE=InnoDB;

-- ── 2 · a set, and what is in it ───────────────────────────────────────────────────────────────
-- `scope` answers a DIFFERENT question from the matrix (design G-3): the matrix says which ACTIONS,
-- scope says which ROWS. 'OWN' = only records this member created, 'ALL' = everything in the shop.
-- Two questions, two controls, so an owner is never guessing what one tick meant.
CREATE TABLE permission_set (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id BIGINT       NULL,          -- NULL = a built-in template, shared by every tenant
    name            VARCHAR(64)  NOT NULL,
    description     VARCHAR(255) NULL,
    scope           VARCHAR(8)   NOT NULL DEFAULT 'OWN',
    is_builtin      TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      DATETIME     NULL,
    updated_at      DATETIME     NULL,
    UNIQUE KEY uq_pset_org_name (organization_id, name),
    KEY idx_pset_org (organization_id)
) ENGINE=InnoDB;

CREATE TABLE permission_set_item (
    set_id          BIGINT      NOT NULL,
    permission_code VARCHAR(64) NOT NULL,
    PRIMARY KEY (set_id, permission_code),
    CONSTRAINT fk_psi_set  FOREIGN KEY (set_id)          REFERENCES permission_set (id) ON DELETE CASCADE,
    CONSTRAINT fk_psi_perm FOREIGN KEY (permission_code) REFERENCES permission (code)
) ENGINE=InnoDB;

-- ── 3 · who is on which set ────────────────────────────────────────────────────────────────────
-- One set per user. Deliberately not many: "which of these five sets won" is a question nobody can
-- answer at a counter, and the override table (phase 3) is the seam for the exception.
CREATE TABLE user_permission_set (
    user_id BIGINT NOT NULL PRIMARY KEY,
    set_id  BIGINT NOT NULL,
    CONSTRAINT fk_ups_set FOREIGN KEY (set_id) REFERENCES permission_set (id)
) ENGINE=InnoDB;

-- ── 4 · the permissions themselves ─────────────────────────────────────────────────────────────
INSERT INTO permission (code, area, action, label, implies, sort_order) VALUES
 ('sale.view',      'sale',     'view',   'See sales',                    NULL, 10),
 ('sale.create',    'sale',     'create', 'Ring up a sale',               'sale.view,product.view,customer.view', 11),
 ('sale.edit',      'sale',     'edit',   'Edit a sale',                  'sale.view,product.view,customer.view', 12),
 ('sale.delete',    'sale',     'delete', 'Delete a sale',                'sale.view', 13),
 ('sale.void',      'sale',     'void',   'Void an invoice',              'sale.view', 14),
 ('sale.discount',  'sale',     'discount','Give a discount',             'sale.view', 15),

 ('purchase.view',  'purchase', 'view',   'See purchases',                NULL, 20),
 ('purchase.create','purchase', 'create', 'Receive stock',                'purchase.view,product.view,supplier.view', 21),
 ('purchase.edit',  'purchase', 'edit',   'Edit a purchase',              'purchase.view,product.view,supplier.view', 22),
 ('purchase.delete','purchase', 'delete', 'Delete a purchase',            'purchase.view', 23),

 ('customer.view',  'customer', 'view',   'See customers',                NULL, 30),
 ('customer.create','customer', 'create', 'Add a customer',               'customer.view', 31),
 ('customer.edit',  'customer', 'edit',   'Edit a customer',              'customer.view', 32),
 ('customer.delete','customer', 'delete', 'Delete a customer',            'customer.view', 33),

 ('product.view',   'product',  'view',   'See products',                 NULL, 40),
 ('product.create', 'product',  'create', 'Add a product',                'product.view', 41),
 ('product.edit',   'product',  'edit',   'Edit a product',               'product.view', 42),
 ('product.delete', 'product',  'delete', 'Delete a product',             'product.view', 43),

 ('supplier.view',  'supplier', 'view',   'See suppliers',                NULL, 50),
 ('supplier.create','supplier', 'create', 'Add a supplier',               'supplier.view', 51),
 ('supplier.edit',  'supplier', 'edit',   'Edit a supplier',              'supplier.view', 52),
 ('supplier.delete','supplier', 'delete', 'Delete a supplier',            'supplier.view', 53),

 ('stock.view',     'stock',    'view',   'See stock',                    NULL, 60),
 ('stock.create',   'stock',    'create', 'Adjust stock in',              'stock.view,product.view', 61),
 ('stock.edit',     'stock',    'edit',   'Correct stock',                'stock.view,product.view', 62),

 ('till.view',      'till',     'view',   'See the till',                 NULL, 70),
 ('till.create',    'till',     'create', 'Open a shift',                 'till.view', 71),
 ('till.close',     'till',     'close',  'Close a shift',                'till.view', 72),

 ('report.view',    'report',   'view',   'See reports',                  NULL, 80),
 ('report.export',  'report',   'export', 'Export reports',               'report.view', 81),

 ('finance.view',   'finance',  'view',   'See finance and the ledger',   NULL, 90),
 ('finance.export', 'finance',  'export', 'Export finance data',          'finance.view', 91),

 ('settings.view',  'settings', 'view',   'See settings',                 NULL, 100),
 ('settings.edit',  'settings', 'edit',   'Change settings',              'settings.view', 101),

 ('team.view',      'team',     'view',   'See the team',                 NULL, 110),
 ('team.create',    'team',     'create', 'Add a team member',            'team.view', 111),
 ('team.edit',      'team',     'edit',   'Edit a team member',           'team.view', 112),
 ('team.delete',    'team',     'delete', 'Remove a team member',         'team.view', 113),

 ('opening.view',   'opening',  'view',   'See opening balances',         NULL, 120),
 ('opening.create', 'opening',  'create', 'Record an opening balance',    'opening.view,customer.view,supplier.view', 121),
 ('opening.reverse','opening',  'reverse','Reverse an opening balance',   'opening.view', 122);

-- ── 5 · the built-in sets ──────────────────────────────────────────────────────────────────────
-- organization_id NULL = a template every tenant sees. 'Standard' and 'Administrator' are NOT
-- examples: they are the CONTRACT that this deploy changes nothing (see the header).
INSERT INTO permission_set (organization_id, name, description, scope, is_builtin, created_at) VALUES
 (NULL, 'Standard',      'What a staff member could already do before permissions existed.', 'OWN', 1, NOW()),
 (NULL, 'Administrator', 'What an admin could already do before permissions existed.',       'ALL', 1, NOW()),
 (NULL, 'Cashier',       'Sells and takes payment. Cannot receive stock or change prices.',  'OWN', 1, NOW()),
 (NULL, 'Storekeeper',   'Receives stock and keeps the product list. Does not sell.',        'ALL', 1, NOW());

-- Standard = exactly today's ROLE_BUSINESS_USER reach: every ungated screen, and nothing that is
-- gated on ROLE_OWNER / ADMIN_PRIVILEGE / SUPER_PRIVILEGE today. So: no finance, no settings, no team,
-- no opening balances, and no void (VOID_INVOICE is a privilege they do not hold).
INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s JOIN permission p
 ON p.code IN ('sale.view','sale.create','sale.edit','sale.discount',
               'purchase.view','purchase.create','purchase.edit',
               'customer.view','customer.create','customer.edit',
               'product.view','product.create','product.edit',
               'supplier.view','supplier.create','supplier.edit',
               'stock.view','stock.create','stock.edit',
               'till.view','till.create','till.close',
               'report.view')
WHERE s.organization_id IS NULL AND s.name = 'Standard';

-- Administrator = Standard + what ADMIN_PRIVILEGE opens today: settings, team, opening balances,
-- void and the deletes. NOT finance — that is owner-only today and stays owner-only.
INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s JOIN permission p
 ON p.area <> 'finance'
WHERE s.organization_id IS NULL AND s.name = 'Administrator';

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s JOIN permission p
 ON p.code IN ('sale.view','sale.create','sale.discount','customer.view','customer.create',
               'product.view','till.view','till.create','till.close','report.view')
WHERE s.organization_id IS NULL AND s.name = 'Cashier';

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, p.code FROM permission_set s JOIN permission p
 ON p.code IN ('purchase.view','purchase.create','purchase.edit','supplier.view','supplier.create',
               'supplier.edit','product.view','product.create','product.edit',
               'stock.view','stock.create','stock.edit','report.view')
WHERE s.organization_id IS NULL AND s.name = 'Storekeeper';

-- ── 6 · ⭐ EVERY EXISTING MEMBER KEEPS EXACTLY WHAT THEY HAVE ───────────────────────────────────
-- Admins onto Administrator, everyone else onto Standard. Owners are deliberately absent: an owner's
-- access is implicit and is not expressed as a set, so they can never edit themselves out of the shop
-- (design G-4). A user already carrying a set is left alone, so this is safe to re-run.
INSERT INTO user_permission_set (user_id, set_id)
SELECT u.id, (SELECT id FROM permission_set WHERE organization_id IS NULL AND name = 'Administrator')
  FROM users u
  JOIN users_roles ur ON ur.user_id = u.id
  JOIN roles r        ON r.id = ur.role_id
 WHERE r.name IN ('ADMIN_ROLE', 'ROLE_BUSINESS_ADMIN')
   AND u.id NOT IN (SELECT user_id FROM user_permission_set)
 GROUP BY u.id;

INSERT INTO user_permission_set (user_id, set_id)
SELECT u.id, (SELECT id FROM permission_set WHERE organization_id IS NULL AND name = 'Standard')
  FROM users u
 WHERE u.id NOT IN (SELECT user_id FROM user_permission_set)
 GROUP BY u.id;
