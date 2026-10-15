-- HMS RW-4b — the owner's overview: "today at a glance" for the clinic and pharmacy (client, 2026-10-13;
-- hms-role-workspaces-design.md section 3.1 Administration). Each figure is a NUMBER CARD that is a row here — the
-- ERPNext pattern — so which cards exist and who sees them is data. The page names each card's key only.
-- The Administration workspace now lands on the overview instead of the pharmacy dashboard.
-- (No dollar-brace anywhere in this file: Flyway would read it as a placeholder, even in a comment.)

INSERT INTO ui_menu (module, menu_key, parent_key, label_key, codes, capability, sort_order)
SELECT v.module, v.k, NULL, v.l, v.c, v.cap, v.o FROM (
          SELECT 'BUSINESS' AS module, 'overview.tokens' AS k, 'ui.ov.tokens' AS l,
                 'clinic.queue.view,clinic.token.issue,clinic.doctor.manage' AS c, 'clinic' AS cap, 960 AS o
UNION ALL SELECT 'BUSINESS', 'overview.waiting', 'ui.ov.waiting',
                 'clinic.queue.view,clinic.token.issue,clinic.doctor.manage', 'clinic', 961
UNION ALL SELECT 'BUSINESS', 'overview.doctors', 'ui.ov.doctors',
                 'clinic.doctor.day,clinic.doctor.manage', 'clinic', 962
UNION ALL SELECT 'BUSINESS', 'overview.prescriptions', 'ui.ov.rx', 'rx.dispense', 'clinic,rxRequired', 963
UNION ALL SELECT 'BUSINESS', 'overview.takings', 'ui.ov.takings', 'report.view', NULL, 964
) v
WHERE NOT EXISTS (SELECT 1 FROM ui_menu m WHERE m.menu_key = v.k);

UPDATE ui_workspace SET url = '/overview' WHERE ws_key = 'admin' AND url = '/businessDashboard';
