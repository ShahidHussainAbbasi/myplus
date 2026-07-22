-- Fees are whole currency units. The entire fee system is already integer — discount.amount, the
-- fee_collection ledger (due_amount / fee / fee_paid / balance) and vehicle fare are all INT — but
-- grade.fee and student.fee were FLOAT, the lone anomaly. Float can't represent whole numbers past 2^24
-- exactly and fed a double percentage-discount calc; make them INT so the model is consistent end to end.
-- MySQL rounds existing float values to whole on ALTER (the ledger already rounded them at monthly-due time).
ALTER TABLE grade   MODIFY COLUMN fee INT;
ALTER TABLE student MODIFY COLUMN fee INT;
