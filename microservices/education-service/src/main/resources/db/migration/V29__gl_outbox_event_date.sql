-- D-6 / step 1 — an education GL event must carry the date the money moved, not the date it was delivered.
--
-- ── The defect, found by replaying one dead-lettered event ──────────────────────────────────────────
-- FeeCollectionController DOES send `.date(LocalDate.now())` on enqueue. gl_outbox had nowhere to put it, so
-- it was dropped at the row, and GlOutboxService.toReq() then hard-coded `.date(LocalDate.now())` AGAIN at
-- DELIVERY time. finance's post() falls back to now() for a null date either way.
--
-- For a delivery that happens seconds after the charge — which is every normal one — that is invisible.
-- For a RETRY it is not. Replaying one 16-August fee on 6 September posted it dated 6 September: real money
-- in the wrong accounting period, with nothing on any screen to show it. The remaining 55 rows are PKR
-- 136,510 of 7-16 August fees that would have landed the same way.
--
-- ── This is business-service's V60, applied to the service that did not get it ──────────────────────
-- myplusdb.gl_outbox already carries event_date for exactly this reason, and its comment says the same thing:
-- "across a month end, into the wrong period, with nothing on screen to show it". Education was written from
-- the same template and missed it, which is the trap already recorded as "a new PostingEventRequest field
-- needs five places or it vanishes" -- the field existed on the DTO at both ends and still fell through.
--
-- ── Nullable, and the backfill is deliberate rather than a default ──────────────────────────────────
-- DATE NULL matches business exactly, so the relay's "fall back to created_at" branch is the same code path.
-- created_at is when the fee was actually collected, which is the truth for every existing row -- and is what
-- makes the 56 dead-lettered events safe to replay into the period they belong to.

ALTER TABLE gl_outbox
    ADD COLUMN event_date DATE NULL AFTER event_key;

-- Every row predates this column, and every one of them was queued at the moment its fee was collected.
UPDATE gl_outbox
   SET event_date = DATE(created_at)
 WHERE event_date IS NULL;
