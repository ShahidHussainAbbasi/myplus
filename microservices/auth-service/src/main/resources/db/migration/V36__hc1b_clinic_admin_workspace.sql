-- HC-1b (2026-10-15) — a Hospital / Clinic's Administration tile opens the clinic's OWN admin work (Team, beside Clinic
-- settings), not the shop owner's overview. Design: microservices/docs/slices/hc-1b-clinic-home-and-setup.md (D2).
--
-- The 'admin' tile (V27, re-pointed to /overview by RW-4b) shows Prescriptions waiting and Takings today — the
-- PHARMACY's figures, linking to /businessDashboard, which a clinic does not have. So:
--   admin        → PHARMA only (the combined clinic + pharmacy keeps exactly what it has until HC-1e splits it)
--   clinic_admin → CLINIC: same label, icon and codes; opens /clinicDashboard?open=TeamDiv
-- Not the Clinic home: that is where the tile is shown, and a tile that opens the page you are on is a dead click.
--
-- Idempotent (D7). No placeholder syntax anywhere in this file (Flyway would substitute it, even in a comment).

UPDATE ui_workspace SET org_types = 'PHARMA' WHERE ws_key = 'admin';

INSERT INTO ui_workspace (module, ws_key, label_key, icon, url, codes, capability, org_types, sort_order)
SELECT 'BUSINESS', 'clinic_admin', 'ui.ws.admin', 'cog', '/clinicDashboard?open=TeamDiv',
       'settings.edit,team.view', 'clinic', 'CLINIC', 50
WHERE NOT EXISTS (SELECT 1 FROM ui_workspace w WHERE w.ws_key = 'clinic_admin');
