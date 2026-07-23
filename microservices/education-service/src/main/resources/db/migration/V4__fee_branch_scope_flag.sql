-- Configurable branch-scoping policy for fee collection. Default FALSE = fees are org-wide (a parent may pay
-- at any campus); an owner can set TRUE to restrict each branch's staff to their own branch's fees.
-- (Attendance stays branch-level unconditionally — a teacher marks only their own branch.)
ALTER TABLE fee_setting ADD COLUMN fee_collection_branch_scoped BIT(1) NOT NULL DEFAULT 0;
