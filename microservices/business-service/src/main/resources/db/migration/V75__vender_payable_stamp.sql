-- FP-4c — what finance knows about a supplier and business cannot: its still-open EXPENSE BILLS and its ADVANCE.
-- Design: microservices/docs/slices/fp-4-payables-reads-switch.md section 4c
--
-- STAMPED by finance (POST /internal/business/payable-balances), never derived here: recomputePayable rewrites
-- due_amount from purchases after every purchase change, so these live in their OWN columns where it cannot touch them.
-- Stamped for every tenant; READ only for a tenant whose supplier figures come from finance (payables_source).
--   payable_other_open    bills still owed to this supplier (never negative)
--   payable_advance       paid ahead across everything (never negative)
--   payable_stamp_version when finance computed them (epoch ms) — an older stamp never overwrites a newer one
ALTER TABLE vender
    ADD COLUMN payable_other_open    DECIMAL(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN payable_advance       DECIMAL(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN payable_stamp_version BIGINT        NOT NULL DEFAULT 0;
