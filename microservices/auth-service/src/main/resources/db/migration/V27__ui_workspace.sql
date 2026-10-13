-- HMS RW-3 — each person lands on their own WORKSPACE (client, 2026-10-12; hms-role-workspaces-design.md section 3.3,
-- decision D4 "both": a tile home for someone with several jobs AND a switcher in the top bar). Like the menus
-- (V25), the workspaces are DATA: a row says where a job lands, which permissions open it and which business setting
-- it needs. The monolith asks auth-service which rows a person has; no workspace is named in code.
--
--   ws_key      the workspace's name
--   label_key   its i18n message key (the tile and the switcher show the person's language)
--   url         where it lands (the clinic page opens the right screen from its open parameter)
--   codes       comma-separated = ANY of them opens it; the owner and an administrator hold all
--   capability  the setting that must be on (comma-separated = any). All five need the CLINIC: a plain shop has no
--               workspace rows that apply, so its sign-in lands exactly where it always did.
-- (No dollar-brace anywhere in this file: Flyway would read it as a placeholder, even in a comment.)

CREATE TABLE IF NOT EXISTS ui_workspace (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    module      VARCHAR(16)  NOT NULL,
    ws_key      VARCHAR(48)  NOT NULL,
    label_key   VARCHAR(96)  NOT NULL,
    icon        VARCHAR(48)  NULL,
    url         VARCHAR(160) NOT NULL,
    codes       VARCHAR(512) NULL,
    capability  VARCHAR(64)  NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ui_workspace_key (ws_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ui_workspace (module, ws_key, label_key, icon, url, codes, capability, sort_order)
SELECT v.module, v.k, v.l, v.i, v.u, v.c, v.cap, v.o FROM (
          SELECT 'BUSINESS' AS module, 'frontdesk' AS k, 'ui.ws.frontdesk' AS l, 'user' AS i,
                 '/clinicDashboard?open=ReceptionDiv' AS u, 'clinic.patient.edit,clinic.token.issue' AS c, 'clinic' AS cap, 10 AS o
UNION ALL SELECT 'BUSINESS', 'doctor',   'ui.ws.doctor',   'heart',
                 '/clinicDashboard?open=MyQueueDiv', 'clinic.consult', 'clinic', 20
UNION ALL SELECT 'BUSINESS', 'pharmacy', 'ui.ws.pharmacy', 'plus-sign',
                 '/businessDashboard', 'rx.dispense', 'clinic', 30
UNION ALL SELECT 'BUSINESS', 'store',    'ui.ws.store',    'shopping-cart',
                 '/businessDashboard', 'purchase.create,stock.edit', 'clinic', 40
UNION ALL SELECT 'BUSINESS', 'admin',    'ui.ws.admin',    'cog',
                 '/businessDashboard', 'settings.edit,team.view', 'clinic', 50
) v
WHERE NOT EXISTS (SELECT 1 FROM ui_workspace w WHERE w.ws_key = v.k);
