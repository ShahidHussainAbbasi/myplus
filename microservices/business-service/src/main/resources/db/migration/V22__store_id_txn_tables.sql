-- Multi-location P2c: the remaining transaction tables gain the store dimension. Sales & purchases got
-- store_id in V21; returns, shifts, cash movements, parked sales and tenders are just as location-bound —
-- without this a Store-B cashier's shift, drawer and returns are indistinguishable from Store A's, so
-- day-close and returns cannot be separated per store.
--
-- Nullable, exactly like V21: legacy rows stay NULL, remain visible to their creator, and drain as they are
-- re-saved. Master data (customer, vendor, catalog, tax, GL) stays org-wide per §2.6 of the design.
-- The invoice HEADER needs the store too: the edit/void/receipt endpoints take an invoice id straight from
-- the client and guard on customer_history, so without a store here they have nothing to check against.
ALTER TABLE customer_history ADD COLUMN store_id BIGINT NULL;
CREATE INDEX idx_ch_store ON customer_history (store_id);

ALTER TABLE sale_return   ADD COLUMN store_id BIGINT NULL;
ALTER TABLE cashier_shift ADD COLUMN store_id BIGINT NULL;
ALTER TABLE cash_movement ADD COLUMN store_id BIGINT NULL;
ALTER TABLE parked_sale   ADD COLUMN store_id BIGINT NULL;
ALTER TABLE payment       ADD COLUMN store_id BIGINT NULL;

CREATE INDEX idx_sale_return_store   ON sale_return   (store_id);
CREATE INDEX idx_cashier_shift_store ON cashier_shift (store_id);
CREATE INDEX idx_cash_movement_store ON cash_movement (store_id);
CREATE INDEX idx_parked_sale_store   ON parked_sale   (store_id);
CREATE INDEX idx_payment_store       ON payment       (store_id);
