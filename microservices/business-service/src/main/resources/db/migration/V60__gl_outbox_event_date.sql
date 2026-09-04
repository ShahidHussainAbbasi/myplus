-- ⚠ A GENERAL LEDGER DEFECT, found by tracing OB-1's posting path end to end. It predates OB-1 and affects
-- every tenant's books today.
--
-- WHAT IS WRONG
-- PostingEventRequest carries a `date` — the day the transaction happened. GlOutboxService.enqueue() copies
-- the event onto a persisted row FIELD BY FIELD, and there was no line for the date, so it was dropped. The
-- relay then rebuilt the request with:
--
--     .date(LocalDate.now())
--
-- so every journal is dated WHEN THE RELAY RAN, not when the sale, purchase or return happened.
--
-- WHAT THAT COSTS
--   * A sale rung at 23:59 and delivered at 00:01 posts to the NEXT DAY. Across a month end it lands in the
--     wrong PERIOD — and period close exists precisely to stop that.
--   * The relay RETRIES. A delivery that fails and succeeds two days later posts two days late.
--   * Neither leaves a trace: the invoice is correctly dated, the journal is not, and the two are only
--     compared when somebody reconciles a month that will not tie out.
--
-- This is the same shape as the `discountTotal` hole recorded in enqueue()'s own comment — "anything without
-- a line here is dropped in silence" — which left 4200 Sales Discount empty in every tenant. Same method,
-- same mechanism, a different field. The comment warned about it and the next field was dropped anyway,
-- which is why the fix carries the date rather than adding another line somebody has to remember.
--
-- WHY OB-1 CANNOT SHIP WITHOUT IT
-- An opening balance MUST post on the tenant's cutover date — that is the whole point of asking for one. With
-- the date dropped it would post today, landing a migration in the current period as though it were current
-- trade, which is exactly the failure OB-1 exists to prevent.
--
-- NULLABLE, deliberately: rows queued before this migration have no event date, and the relay falls back to
-- created_at for them (closer to the truth than now(), because it is when the transaction actually happened).

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'gl_outbox' AND column_name = 'event_date') = 0,
    'ALTER TABLE gl_outbox ADD COLUMN event_date DATE NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill the queue that already exists from created_at. PENDING rows then deliver on the day they were
-- raised rather than the day the relay next wakes up.
UPDATE gl_outbox SET event_date = DATE(created_at) WHERE event_date IS NULL AND created_at IS NOT NULL;
