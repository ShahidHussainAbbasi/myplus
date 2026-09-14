-- BLK-0c — OPTIMISTIC LOCKING ON THE PAYMENT LEDGER.
--
-- WHAT THIS PROTECTS
-- Two people can reach the same payment: one re-allocating a receipt across invoices while another edits or
-- reverses it. Without a version, the second write silently wins and the first person's allocation is gone
-- with nothing anywhere saying so. That is last-write-wins on money, which §0b of the build standards
-- refuses outright: "a customer's phone number can be optimistic; a receivable cannot."
--
-- WHY NOT A TIMESTAMP
-- `created_at` already exists and is tempting. It is the wrong tool for the same reasons it was wrong for
-- Customer (V62): it is set BY the write being validated, it has second fidelity, and two writes inside one
-- second compare equal. A counter JPA owns has none of those ambiguities — Hibernate puts it in the UPDATE's
-- WHERE clause and the row count tells it whether anyone got there first.
--
-- ⚠ NOT NULL DEFAULT 0, AND THAT COMBINATION IS LOAD-BEARING
-- A NULL version makes Hibernate treat an existing row as TRANSIENT and attempt an INSERT — which would
-- DUPLICATE every payment already in the ledger on its next edit. On Customer that would have duplicated
-- 3,383 contact rows; here it would duplicate money. The DEFAULT is not a convenience, it is what keeps
-- every existing row updatable.
--
-- ⚠ `ddl-auto=validate` means the column must match `@Version private Long version` EXACTLY, or
-- finance-service does not start. BIGINT because the entity field is Long and validate compares types.
--
-- Both tables, because an allocation is edited independently of its parent payment: re-pointing a receipt at
-- a different invoice writes the allocation row, not the payment row, so versioning only the parent would
-- leave the row people actually contend over unprotected.

ALTER TABLE payments
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_allocations
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
