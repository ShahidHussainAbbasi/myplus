-- Money as BigDecimal, not float (SaaS money standard; business/finance/agriculture already there).
-- donation.amount / donator.amount were FLOAT — binary float can't represent currency exactly, so donation
-- totals could drift. DECIMAL(19,2). MySQL converts existing FLOAT values on ALTER (frozen at 2dp).
ALTER TABLE donation MODIFY COLUMN amount DECIMAL(19,2);
ALTER TABLE donator  MODIFY COLUMN amount DECIMAL(19,2);
