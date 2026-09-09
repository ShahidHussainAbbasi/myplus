-- PERM-1 fix-forward: a permission set belongs to a BUSINESS member, and to nobody else.
--
-- ⚠ WHAT WENT WRONG, because it is worth stating plainly: V12's second INSERT read "everyone not
-- already placed", and there are four other modules and a parent portal in this database. So a
-- ROLE_GUARDIAN — a parent signing in to see their child's attendance — was placed on `Standard`, a set
-- built for a SHOP, granting sale.create, purchase.create and customer.create. Same for every pharma,
-- welfare, agriculture, appointment, marketplace, campaign and analytics user.
--
-- Nothing was exploitable through a screen: those users reach a different dashboard and the monolith's
-- PermissionInterceptor maps no education or portal path. But it was a genuine privilege escalation
-- sitting in the token, and "not reachable today" is not a defence — it is the definition of a latent
-- one.
--
-- The catalog is business-shaped by design (sale, purchase, till, opening balances). Until another
-- module has a catalog of its own, its members hold NO set, and AuthService then mints exactly the role
-- privileges it minted before PERM-1 existed. That is the same fail-back the claim already uses.
DELETE ups FROM user_permission_set ups
 WHERE NOT EXISTS (
       SELECT 1
         FROM memberships m
         JOIN organizations o ON o.id = m.organization_id
        WHERE m.user_id = ups.user_id
          AND o.type = 'BUSINESS');
