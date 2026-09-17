-- PERM-2 — a PHARMACY member gets the same permission set a shop member got.
-- Design: microservices/docs/slices/perm-2-pharma-permission-codes.md
--
-- WHAT WAS WRONG:
-- A pharmacy reuses the commerce core — same dashboard, same till, same purchase screen, same
-- PermissionInterceptor map — but its organisation's type is PHARMA, and AuthService minted PERM-1 codes only
-- for type BUSINESS. So every non-owner pharmacy member carried ZERO codes and was refused every mapped action
-- ("You are not allowed to add products" on POST /addProduct, verified live 2026-09-14). The affordances gated
-- on those codes simply vanished: "Register a new product" on the purchase screen is
-- sec:authorize="hasAuthority('product.create')", which — unlike the server-side check — has no owner bypass,
-- so it was invisible to the pharmacy's OWNER too.
--
-- The code half of the fix is AuthService.tradeTenant (BUSINESS or PHARMA). This is the other half: minting
-- alone changes nothing for existing members, because V14 DELETED their rows. Without this they would hold a
-- set-shaped nothing and stay refused.
--
-- WHY V14 WAS RIGHT AND THIS IS NOT A REVERSAL:
-- V14 removed shop sets from users who do not trade — a ROLE_GUARDIAN parent had been given sale.create. That
-- stands. This restores them for the ONE vertical that does trade on the same screens. Education, welfare,
-- agriculture, appointment, campaign and analytics remain excluded; MARKETPLACE is deliberately left out
-- pending its own ruling (its members reach a different dashboard).
--
-- MIRRORS V12 EXACTLY, so a pharmacy member ends up with what the same person would have had in a shop:
--   ADMIN_ROLE / ROLE_BUSINESS_ADMIN  -> Administrator
--   everyone else                     -> Standard
--   ROLE_OWNER                        -> no set at all (V13: an owner's access is implicit and cannot be
--                                       edited away from them)
--
-- Idempotent: every INSERT excludes users who already hold a set, so a re-run is a no-op. Safe on a database
-- where this has already been applied, and safe to run before or after the auth-service deploy.

-- Admins of a PHARMA tenant -> Administrator.
INSERT INTO user_permission_set (user_id, set_id)
SELECT u.id, (SELECT id FROM permission_set WHERE organization_id IS NULL AND name = 'Administrator')
  FROM users u
  JOIN memberships m  ON m.user_id = u.id
  JOIN organizations o ON o.id = m.organization_id AND o.type = 'PHARMA'
  JOIN users_roles ur ON ur.user_id = u.id
  JOIN roles r        ON r.id = ur.role_id
 WHERE r.name IN ('ADMIN_ROLE', 'ROLE_BUSINESS_ADMIN')
   AND u.id NOT IN (SELECT user_id FROM user_permission_set)
   AND u.id NOT IN (SELECT ur2.user_id FROM users_roles ur2
                      JOIN roles r2 ON r2.id = ur2.role_id
                     WHERE r2.name = 'ROLE_OWNER')
 GROUP BY u.id;

-- Every other PHARMA member -> Standard (owners excluded, per V13).
INSERT INTO user_permission_set (user_id, set_id)
SELECT u.id, (SELECT id FROM permission_set WHERE organization_id IS NULL AND name = 'Standard')
  FROM users u
  JOIN memberships m  ON m.user_id = u.id
  JOIN organizations o ON o.id = m.organization_id AND o.type = 'PHARMA'
 WHERE u.id NOT IN (SELECT user_id FROM user_permission_set)
   AND u.id NOT IN (SELECT ur2.user_id FROM users_roles ur2
                      JOIN roles r2 ON r2.id = ur2.role_id
                     WHERE r2.name = 'ROLE_OWNER')
 GROUP BY u.id;
