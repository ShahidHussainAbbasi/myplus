-- Money as BigDecimal, not float. amount was FLOAT — binary float can't represent currency exactly
-- (e.g. 1234.56 is stored as an approximation), so income/expense totals could drift by fractions.
-- Bring it in line with the business/finance money standard: DECIMAL(19,2). MySQL converts existing
-- FLOAT values on ALTER; any pre-existing rounding error is frozen at 2dp (it does not get worse).
ALTER TABLE agriculture_expense MODIFY COLUMN amount DECIMAL(19,2);
ALTER TABLE agriculture_income  MODIFY COLUMN amount DECIMAL(19,2);
