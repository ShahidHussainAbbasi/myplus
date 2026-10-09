-- AN-1: analytics-service's first producer. Finance's P&L is stored month by month (finance.revenue / finance.expenses)
-- and served back for the P&L trend, so two things the table could not do before:
--
-- 1. Add up to the books to the cent. `value` was DOUBLE: 0.1 + 0.2 summed as doubles is not 0.3, and a trend that is
--    a cent off the P&L it sits under is a trend nobody trusts. DECIMAL(19,2), the money type used everywhere else.
--    (Nothing has ever written a row, so no value changes.)
-- 2. Hold ONE row per tenant, metric and period. Every read refreshes the months it shows; without a key two readers
--    at once insert the same month twice and the next sum doubles it. The write is INSERT ... ON DUPLICATE KEY UPDATE
--    on this key. A metric split by a dimension must use its own metric_name (dimension is not in the key: NULLs do
--    not collide in a MySQL unique index, so it would guard nothing).
ALTER TABLE aggregated_metrics MODIFY COLUMN `value` DECIMAL(19,2) NOT NULL;
CREATE UNIQUE INDEX uk_aggregated_metrics_period ON aggregated_metrics (organization_id, metric_name, period_type, period_start);
