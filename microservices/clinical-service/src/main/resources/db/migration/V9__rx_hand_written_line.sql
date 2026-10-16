-- HC-1d-2 — a doctor of a SEPARATE clinic may prescribe a medicine that is not in the linked pharmacy's list, written by
-- hand (the user's D2, 2026-10-15). Such a line has no product: product_id becomes NULLable. The pharmacist chooses the
-- product (Change) before dispensing. Only the linked route allows it (ConsultService.saveRx); a template still needs a
-- product on every line (rx_template_item is unchanged), and the combined org's own pharmacy still requires one.
ALTER TABLE encounter_rx_item MODIFY COLUMN product_id BIGINT NULL;
