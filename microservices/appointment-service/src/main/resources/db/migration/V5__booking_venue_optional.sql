-- Slice SCHED-1 (B2) — a booking need not have a venue.
-- Design: microservices/docs/slices/sched-1-scheduling-core.md
--
-- ── Found by the gate, not by reading ────────────────────────────────────────────────────────────
-- `booking.venue_id` has been NOT NULL since the clinic baseline, and for a clinic that is right: every
-- appointment happens at a hospital. It is wrong for a neutral core. A parents' evening is "ten minutes
-- with Miss Khan at 18:20" — the venue is the school, which is not a row anybody creates.
--
-- The symptom was worse than the cause: every slot booking failed the NOT NULL check, the service's catch
-- reported it as "already booked", and the API answered 200 with nothing written. The over-broad catch is
-- fixed in the same change; this migration removes the reason it fired.
--
-- WIDENING only — no existing row is touched, and every clinic booking keeps its venue. A widening cannot
-- fail on live data, which is why it needs no audit the way a new constraint does (standard D5).

SET @sql := IF((SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND column_name = 'venue_id') = 'NO',
    'ALTER TABLE booking MODIFY COLUMN venue_id BIGINT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
