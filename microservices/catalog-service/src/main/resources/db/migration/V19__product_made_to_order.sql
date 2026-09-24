-- RST — MADE TO ORDER: items assembled when ordered, which hold no finished stock.
--
-- THE GAP THIS CLOSES, AND HOW IT WAS FOUND
-- SagaSellService builds a StockReservationLine for EVERY sale line with no exemption, so the platform
-- cannot sell anything it does not physically hold. The restaurant R1 gate ran expecting to pass and
-- answered "Not enough sellable stock — 'Zinger Burger': only 0 sellable, 2 requested". The product was
-- right and the design was wrong: a restaurant holds buns, fillets and oil, and assembles a burger when the
-- order lands. A salon cannot stock a haircut either, so this is not restaurant-specific.
--
-- ⚠ NOT NULL DEFAULT FALSE, AND THAT COMBINATION IS THE WHOLE SAFETY ARGUMENT
-- Every existing row becomes explicitly "reserves stock", which is exactly today's behaviour, so this deploy
-- changes nothing for any tenant. The exemption is opt-in per item and can only ever narrow from there.
--
-- ⚠ WHY PER PRODUCT AND NOT A TENANT SWITCH
-- BusinessSettingsCatalog records that `pos.sale.negativeStockAllowed` was deliberately REMOVED, with a
-- warning not to re-add one without building the cross-service oversell path behind it. A tenant-wide switch
-- would re-open precisely that, and for every product at once — including the ones the shop genuinely holds,
-- where "only 0 sellable" is the system working correctly. A restaurant still stocks cold drinks.
--
-- Idempotent in V11's idiom: dev runs ddl-auto:update and may already have added the column from the entity.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='made_to_order')=0,
    'ALTER TABLE products ADD COLUMN made_to_order TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
