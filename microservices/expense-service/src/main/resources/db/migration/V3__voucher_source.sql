-- EX-3 — where a voucher came from: typed on the Expenses screen (MANUAL) or a till pay-out (DRAWER).
-- Design: microservices/docs/slices/ex-3-till-pay-outs.md
--
-- source_ref is the originating record's id (the drawer movement). UNIQUE (organization_id, source, source_ref)
-- is the IDEMPOTENT RECEIVER: business-service's outbox may deliver the same pay-out twice; the second insert
-- loses on this index and the first voucher is returned — never a second expense (DUP-1: the index carries it).
-- MySQL treats NULLs as distinct, so every MANUAL voucher (source_ref NULL) is unaffected.
ALTER TABLE expense_voucher
    ADD COLUMN source     VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN source_ref VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_expense_voucher_org_source_ref ON expense_voucher (organization_id, source, source_ref);
