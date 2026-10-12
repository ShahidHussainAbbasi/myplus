-- HMS RW-0b — menus are DATA (client, 2026-10-12: "nothing should be hard coded … populated from the databases";
-- hms-role-workspaces-design.md §8). Which permission codes unlock a menu used to be written into the templates; it
-- is now a row here, and a page names only the menu's KEY (${@perm.menu('purchase')}).
--
--   menu_key     the name the page uses — unique
--   parent_key   the menu this item sits in (NULL = a top-level menu)
--   codes        the permission codes that unlock it, comma-separated = ANY of them; NULL = everyone signed in.
--                The owner and an administrator see every menu (as the server lets them do every action).
--   capability   the business setting that must also be on (comma-separated = any); NULL = always. The pages also
--                hide by setting in the browser ([data-capability]); this column lets a menu say it as data too.
--
-- Rows follow the RW-2 rulings (2026-10-12): the front desk keeps the till and selling (D1) and a doctor's day (D2),
-- the pharmacist sells and dispenses but does not receive stock (D3).

CREATE TABLE IF NOT EXISTS ui_menu (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    module      VARCHAR(16)  NOT NULL,
    menu_key    VARCHAR(64)  NOT NULL,
    parent_key  VARCHAR(64)  NULL,
    label_key   VARCHAR(96)  NULL,
    codes       VARCHAR(512) NULL,
    capability  VARCHAR(64)  NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ui_menu_key (menu_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ui_menu (module, menu_key, parent_key, label_key, codes, capability, sort_order)
SELECT v.module, v.menu_key, v.parent_key, v.label_key, v.codes, v.capability, v.sort_order FROM (
          SELECT 'BUSINESS' AS module, 'register' AS menu_key, NULL AS parent_key, 'ui.register' AS label_key,
                 'supplier.view,supplier.create,customer.view,customer.create,product.view,product.create,product.edit,stock.edit,stock.create,settings.edit' AS codes,
                 NULL AS capability, 100 AS sort_order
UNION ALL SELECT 'BUSINESS', 'register.company',     'register', 'ui.company',          'settings.edit', NULL, 101
UNION ALL SELECT 'BUSINESS', 'register.supplier',    'register', 'ui.venderSupplier2',  'supplier.view,supplier.create', NULL, 102
UNION ALL SELECT 'BUSINESS', 'register.customer',    'register', 'ui.customer',         'customer.view,customer.create', NULL, 103
UNION ALL SELECT 'BUSINESS', 'register.products',    'register', 'ui.product',          'product.view,product.create,product.edit', NULL, 104
UNION ALL SELECT 'BUSINESS', 'register.stockCount',  'register', 'ui.stockCount',       'stock.edit,stock.create', NULL, 105
UNION ALL SELECT 'BUSINESS', 'register.labels',      'register', 'ui.labels',           'product.edit,product.create', NULL, 106
UNION ALL SELECT 'BUSINESS', 'register.quarantine',  'register', 'ui.quarantine',       'stock.edit', 'batchTracking,expiryTracking', 107
UNION ALL SELECT 'BUSINESS', 'purchase',             NULL,       'ui.purchase',         'purchase.view,purchase.create,purchase.edit', NULL, 200
UNION ALL SELECT 'BUSINESS', 'purchase.new',         'purchase', 'ui.newPurchase',      'purchase.create', NULL, 201
UNION ALL SELECT 'BUSINESS', 'purchase.debitNotes',  'purchase', 'ui.debitNotes',       'purchase.view,purchase.create', NULL, 202
UNION ALL SELECT 'BUSINESS', 'sale',                 NULL,       'ui.sell',             'sale.view,sale.create', NULL, 300
UNION ALL SELECT 'BUSINESS', 'sale.new',             'sale',     'ui.newSale',          'sale.create', NULL, 301
UNION ALL SELECT 'BUSINESS', 'sale.parked',          'sale',     'ui.parkedSales',      'sale.create', NULL, 302
UNION ALL SELECT 'BUSINESS', 'sale.detailReport',    'sale',     'ui.saleDetailReport', 'sale.view', NULL, 303
UNION ALL SELECT 'BUSINESS', 'sale.creditNotes',     'sale',     'ui.creditNotes',      'sale.view', NULL, 304
UNION ALL SELECT 'BUSINESS', 'sale.quotes',          'sale',     'ui.quotes',           'sale.create', 'dealerPricing', 305
UNION ALL SELECT 'BUSINESS', 'sale.installments',    'sale',     'ui.js.instScreenTitle', 'sale.view', 'installments', 306
UNION ALL SELECT 'BUSINESS', 'sale.marketplace',     'sale',     'ui.mktSellMenu',      'sale.create', 'marketplaceSelling', 307
UNION ALL SELECT 'BUSINESS', 'store',                NULL,       'ui.store',            'sale.view,sale.create', NULL, 400
UNION ALL SELECT 'BUSINESS', 'pharmacy',             NULL,       'ui.pharmacy',
                 'rx.dispense,sale.view,stock.edit,product.edit,clinic.patient.edit,clinic.token.issue,clinic.token.cancel,clinic.consult,clinic.doctor.day,clinic.doctor.manage,clinic.settings', NULL, 500
UNION ALL SELECT 'BUSINESS', 'pharmacy.prescriptions', 'pharmacy', 'ui.prescriptions',  'rx.dispense,sale.view', 'rxRequired', 501
UNION ALL SELECT 'BUSINESS', 'pharmacy.clinical',    'pharmacy', 'ui.clinicalAndSafety', 'rx.dispense,product.edit', 'rxRequired', 502
UNION ALL SELECT 'BUSINESS', 'pharmacy.alerts',      'pharmacy', 'ui.alertsAndRegister', 'rx.dispense,stock.edit', 'expiryTracking,rxRequired', 503
UNION ALL SELECT 'BUSINESS', 'pharmacy.clinic',      'pharmacy', 'ui.clinicOpen',
                 'clinic.patient.edit,clinic.token.issue,clinic.token.cancel,clinic.consult,clinic.doctor.day,clinic.doctor.manage,clinic.settings', 'clinic', 504
UNION ALL SELECT 'BUSINESS', 'till',                 NULL,       'ui.till',             'till.view,till.create', NULL, 600
-- the clinic page (clinicDashboard) — the same rules clinical-service enforces (ClinicAccess)
UNION ALL SELECT 'BUSINESS', 'clinic.reception',     NULL, 'ui.clinicReception', 'clinic.patient.edit,clinic.token.issue', 'clinic', 700
UNION ALL SELECT 'BUSINESS', 'clinic.queue',         NULL, 'ui.clinicQueue',     'clinic.token.issue,clinic.token.cancel', 'clinic', 701
UNION ALL SELECT 'BUSINESS', 'clinic.doctors',       NULL, 'ui.clinicDoctors',   'clinic.doctor.day,clinic.doctor.manage', 'clinic', 702
UNION ALL SELECT 'BUSINESS', 'clinic.doctors.manage', 'clinic.doctors', NULL,    'clinic.doctor.manage', 'clinic', 703
UNION ALL SELECT 'BUSINESS', 'clinic.doctors.day',   'clinic.doctors', NULL,     'clinic.doctor.day', 'clinic', 704
UNION ALL SELECT 'BUSINESS', 'clinic.settings',      NULL, 'ui.clinicSettings',  'clinic.settings', 'clinic', 705
UNION ALL SELECT 'BUSINESS', 'clinic.pharmacy',      NULL, 'ui.clinicToPharmacy',
                 'sale.view,sale.create,rx.dispense,purchase.view,product.view,stock.view,till.view', NULL, 706
-- in-page actions (a button or a flag the page reads): the same rule, so a page names no code at all
UNION ALL SELECT 'BUSINESS', 'action.product.add',   NULL, NULL, 'product.create', NULL, 900
UNION ALL SELECT 'BUSINESS', 'action.product.edit',  NULL, NULL, 'product.edit', NULL, 901
UNION ALL SELECT 'BUSINESS', 'action.rx.dispense',   NULL, NULL, 'rx.dispense', NULL, 902
) v
WHERE NOT EXISTS (SELECT 1 FROM ui_menu m WHERE m.menu_key = v.menu_key);
