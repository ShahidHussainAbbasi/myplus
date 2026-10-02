-- EX-2b — what an expense line was FOR: a school, a vehicle, a land. A reporting dimension, never an account.
-- Design: microservices/docs/slices/ex-2b-expense-tags.md
--
-- tag_type + tag_id reference a record the OWNING module confirmed for the caller at save (expense-service
-- never stores an unconfirmed id); tag_label is a SNAPSHOT, so renaming the bus never rewrites history.
-- Types match their entity columns exactly (STANDARDS D: ddl-auto=validate). Additive, all NULL: every
-- existing line is simply untagged.
ALTER TABLE expense_voucher_line
    ADD COLUMN tag_type  VARCHAR(16)  NULL,
    ADD COLUMN tag_id    BIGINT       NULL,
    ADD COLUMN tag_label VARCHAR(160) NULL;

-- Reports group by what a cost was for ("what does each bus cost?"): index the predicate they run (D3b).
CREATE INDEX idx_expense_line_tag ON expense_voucher_line (tag_type, tag_id);
