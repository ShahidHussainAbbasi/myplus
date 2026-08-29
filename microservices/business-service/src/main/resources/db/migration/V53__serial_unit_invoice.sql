-- SER-3 — which sale a unit left on.
--
-- WHY invoice_no AND NOT sell_id
-- V52 provisioned sell_id for a per-LINE link, but the sale path does not expose one: SagaSaleWriter returns
-- the CustomerHistory (the invoice), and the individual Sell rows are written inside it. Rather than store an
-- invoice id in a column named sell_id — misleading in exactly the way that costs somebody an afternoon two
-- years from now — this records the invoice NUMBER, which is also what a warranty claim or a police enquiry
-- actually quotes. sell_id stays for a per-line link if one is ever needed.
--
-- Indexed because the question it answers is a LOOKUP: "this handset came back — which sale was it on?"
--
-- ⚠ V53: V52 is the highest existing. A duplicate version does not fail, it is SILENTLY SKIPPED.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='serial_unit' AND COLUMN_NAME='invoice_no')=0,
    'ALTER TABLE serial_unit ADD COLUMN invoice_no VARCHAR(32) NULL', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='serial_unit'
                  AND INDEX_NAME='idx_serial_unit_org_invoice')=0,
    'CREATE INDEX idx_serial_unit_org_invoice ON serial_unit (organization_id, invoice_no)', 'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
