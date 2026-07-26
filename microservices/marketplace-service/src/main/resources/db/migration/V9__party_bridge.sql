-- Party bridge (P3): link a marketplace StorefrontCustomer (online shopper account) to the shared party/contact
-- master (party-service), so an online shopper dedupes to the same party as their POS customer / other roles (by
-- email). party_id is stamped best-effort AFTER registration commits, via runAs(storefront, orgId) because the
-- register endpoint is anonymous/public (no authenticated identity to forward). NULL until bridged — additive.
-- Idempotent (dev ddl-auto:update also adds it): guarded ADD.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='storefront_customer' AND COLUMN_NAME='party_id')=0,
    'ALTER TABLE storefront_customer ADD COLUMN party_id BIGINT DEFAULT NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
