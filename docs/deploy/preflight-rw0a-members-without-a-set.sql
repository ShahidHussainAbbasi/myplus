-- ============================================================================================================
-- PRE-FLIGHT RW-0a — who auth-service V24 places on a permission set. READ-ONLY. Run against the auth database
-- (myplusdb_auth) BEFORE deploying, and read every row.          microservices/docs/hms-role-workspaces-design.md §8
--
-- Client decision (2026-10-12): members of a business who are on NO permission set move to their business's
-- default set — an ADMIN to the "Administrator" built-in (default_for = ADMIN), everyone else to "Standard"
-- (default_for = MEMBER). From then on everyone holds exactly what their set says; the code fallback that gave
-- no-set members the dispensing and front-desk codes is removed.
--
-- If a row below should NOT get Standard (e.g. the person only ever looked at reports), move them to the right
-- set on Team after the deploy.
-- ============================================================================================================

SELECT o.id AS organization_id, o.name AS business, u.email, m.role AS membership_role,
       CASE WHEN m.role = 'ADMIN' THEN 'Administrator' ELSE 'Standard' END AS will_be_placed_on
  FROM memberships m
  JOIN organizations o ON o.id = m.organization_id AND o.type IN ('BUSINESS', 'PHARMA')
  JOIN users u ON u.id = m.user_id
 WHERE m.status = 'ACTIVE' AND m.role IN ('USER', 'ADMIN')
   AND NOT EXISTS (SELECT 1 FROM user_permission_set s WHERE s.user_id = m.user_id)
 ORDER BY o.id, u.email;
