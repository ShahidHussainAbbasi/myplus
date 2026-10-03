-- MKT manual walk — reset the never-applied shop (owner.audit@myplus.com) BEFORE a recording or a manual re-walk.
--
-- TEST ENVIRONMENTS ONLY. Accepting the seller and data-sharing agreements is a permanent record by design: the
-- product has no "un-apply", so M-0a-03 ("the owner switches it on, reads the agreements and applies") can only be
-- walked once per shop. This removes that one shop's marketplace application so the walk can start from the
-- beginning again. It touches no other shop and nothing outside the marketplace schema.
--
-- The entitlement and the shop's own switch are put back by the walk's own cleanup (M-0a-06), through the screens.
--
-- Run: docker exec -i mkt-mysql mysql -uroot -p<password> < microservices/docs/marketplace/walk-reset.sql

SET @org := (SELECT o.id FROM myplusdb_auth.organizations o
             JOIN myplusdb_auth.users u ON u.id = o.owner_user_id
             WHERE u.email = 'owner.audit@myplus.com');

DELETE FROM myplusdb_marketplace.mkt_agreement_acceptance WHERE organization_id = @org;
DELETE FROM myplusdb_marketplace.mkt_seller_account       WHERE organization_id = @org;

SELECT @org AS reset_organization_id,
       (SELECT COUNT(*) FROM myplusdb_marketplace.mkt_seller_account WHERE organization_id = @org) AS seller_accounts_left;
