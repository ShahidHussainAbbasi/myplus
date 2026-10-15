-- PERF-R1 preflight (2026-10-15) — READ ONLY. Run on PRODUCTION before any R1 deploy, paste the result back.
-- Lists every base table with an organization_id column and how many of its rows have NONE (pre-tenancy rows).
-- R1 removes the "organization_id IS NULL AND user_id = ?" fallback from scoped reads; a table that still holds
-- such rows must be backfilled first, or those rows would vanish from their owner's screens.
-- Expected by design: auth.permission_set (built-in sets) and notification_* (system mail sent before sign-in).
-- Dev result 2026-10-15: 188 tables, 202,041 rows; the only legacy rows were inventory.stock_adjustments (34).

SET SESSION group_concat_max_len = 1000000;
SELECT GROUP_CONCAT(CONCAT('SELECT ''', c.table_schema, '.', c.table_name, ''' AS tbl, COUNT(*) AS total_rows, ',
                           'COALESCE(SUM(organization_id IS NULL),0) AS null_org FROM `', c.table_schema, '`.`', c.table_name, '`')
                    SEPARATOR ' UNION ALL ')
  INTO @q
  FROM information_schema.columns c
  JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
 WHERE c.table_schema LIKE 'myplus%' AND c.column_name = 'organization_id';
SET @q = CONCAT('SELECT * FROM (', @q, ') x WHERE null_org > 0 ORDER BY null_org DESC');
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;
