-- FP-5a — a payment reaches the ledger exactly once.
-- Design: microservices/docs/slices/fp-5-one-settlement-path.md section 4
--
-- client_ref is the CALLER's reference for one settlement (BUS-PAYV-<org>-<key>, BUS-RCV-…, EDU-FEE-…). The callers
-- now deliver through an outbox that retries, so the same request can arrive twice (a lost answer, a redelivery).
-- UNIQUE (organization_id, client_ref) makes the second arrival return the FIRST payment instead of a second payment
-- and a second journal. MySQL treats NULLs as distinct, so every existing payment (no reference) is unaffected.
ALTER TABLE payments ADD COLUMN client_ref VARCHAR(100) NULL;
CREATE UNIQUE INDEX uq_payment_org_client_ref ON payments (organization_id, client_ref);
