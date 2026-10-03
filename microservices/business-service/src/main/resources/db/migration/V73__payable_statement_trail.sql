-- FP-4a — the payable snapshot now carries the statement trail (the bill as issued, and its debit notes).
-- Design: microservices/docs/slices/fp-4-payables-reads-switch.md §3
--
-- payload 2000 → 8000: a purchase's debit notes ride inside its snapshot (a few per bill in practice; 8000 leaves
-- room for dozens). VARCHAR, not TEXT/JSON, so the ddl-auto=validate contract stays a plain length.
ALTER TABLE payable_outbox MODIFY COLUMN payload VARCHAR(8000) NOT NULL;

-- generation: which version of the snapshot a tenant's backfill sent. FP-2 sent generation 1 (no trail); every
-- tenant below 2 is replayed once more so finance learns issued amounts and debit notes for existing purchases —
-- automatically, on every deploy, with no manual step (finance's intake is idempotent: a replay changes only the trail).
ALTER TABLE payable_backfill ADD COLUMN generation INT NOT NULL DEFAULT 1;
