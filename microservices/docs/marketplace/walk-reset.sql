-- MKT manual walk — reset the never-applied shop (owner.audit@myplus.com) BEFORE a recording or a manual re-walk.
--
-- TEST ENVIRONMENTS ONLY. Accepting the seller and data-sharing agreements is a permanent record by design: the
-- product has no "un-apply", so M-0a-03 ("the owner switches it on, reads the agreements and applies") can only be
-- walked once per shop. This removes that one shop's marketplace application so the walk can start from the
-- beginning again. It touches no other shop. It also adds a second operator for MKT-1g (below, auth schema).
--
-- The entitlement and the shop's own switch are put back by the walk's own cleanup (M-0a-06), through the screens.
--
-- Run: docker exec -i myplus-mysql mysql -uroot -p<password> < microservices/docs/marketplace/walk-reset.sql

SET @org := (SELECT o.id FROM myplusdb_auth.organizations o
             JOIN myplusdb_auth.users u ON u.id = o.owner_user_id
             WHERE u.email = 'owner.audit@myplus.com');

DELETE FROM myplusdb_marketplace.mkt_agreement_acceptance WHERE organization_id = @org;
DELETE FROM myplusdb_marketplace.mkt_seller_account       WHERE organization_id = @org;

SELECT @org AS reset_organization_id,
       (SELECT COUNT(*) FROM myplusdb_marketplace.mkt_seller_account WHERE organization_id = @org) AS seller_accounts_left;

-- ── MKT-1g: a SECOND operator. A payout needs two people (the one who requests it cannot approve it), and the dev
--    seed creates one operator. ops2@myplus.com signs in with the same password as admin@myplus.com. Idempotent. ──
INSERT INTO myplusdb_auth.users (account_non_locked, created_at, email, enabled, failed_login_attempts, first_name,
                                 last_name, password, two_factor_enabled, updated_at, user_type, username, demo)
SELECT account_non_locked, NOW(6), 'ops2@myplus.com', enabled, 0, 'Second', 'Operator', password, two_factor_enabled,
       NOW(6), user_type, 'ops2', demo
FROM myplusdb_auth.users WHERE email = 'admin@myplus.com'
  AND NOT EXISTS (SELECT 1 FROM myplusdb_auth.users WHERE email = 'ops2@myplus.com');
INSERT INTO myplusdb_auth.users_roles (user_id, role_id)
SELECT u.id, r.id FROM myplusdb_auth.users u JOIN myplusdb_auth.roles r ON r.name = 'ROLE_ADMIN'
WHERE u.email = 'ops2@myplus.com'
  AND NOT EXISTS (SELECT 1 FROM myplusdb_auth.users_roles x WHERE x.user_id = u.id AND x.role_id = r.id);

SELECT (SELECT COUNT(*) FROM myplusdb_auth.users WHERE email = 'ops2@myplus.com') AS second_operator;
