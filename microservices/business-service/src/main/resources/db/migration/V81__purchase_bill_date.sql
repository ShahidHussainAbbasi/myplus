-- H6 C3b (client ruling 2026-10-11, docs/hms-phase1-design.md §4l) — a purchase's BILL DATE as a real DATE.
--
-- `dated` is the recorded instant (UTC in the JVM). The bill's date is a CALENDAR DAY and must never move with a
-- zone (Salesforce Date / Odoo fields.Date). Every accounting reader of a purchase reads bill_date: the payables
-- document date sent to finance, supplier aging and statements, the period lock, payment allocation order.
--
-- Back-fill: the database stores the BUSINESS's wall-clock time (the JDBC connection runs at +05:00 — see
-- project_db_timezone_standard), so DATE(dated) IS the business day of every existing row:
--   * server-stamped purchases recorded 00:00–05:00 Karachi (UTC the day before) get their Karachi day — the fix;
--   * typed bill dates (held as UTC midnight = 05:00:00 here) keep the day that was typed.
-- New rows are stamped by Purchase.stampBillDate() (@PrePersist). Idempotent: only rows still NULL are filled.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'purchase' AND COLUMN_NAME = 'bill_date') = 0,
    'ALTER TABLE purchase ADD COLUMN bill_date DATE NULL',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE purchase SET bill_date = DATE(dated) WHERE bill_date IS NULL AND dated IS NOT NULL;
