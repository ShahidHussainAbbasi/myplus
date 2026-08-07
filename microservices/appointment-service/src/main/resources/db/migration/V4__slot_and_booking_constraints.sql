-- Slice SCHED-1 (B2) — make double-booking impossible, and add real time SLOTS.
-- Design: microservices/docs/slices/sched-1-scheduling-core.md
--
-- ══ A CORRECTION TO THIS SLICE'S OWN §9d CLAIM, made after reading the service instead of inferring ══
--
-- The programme (§9d) and this slice's §1 both said: "two patients can book the same doctor at the same
-- minute". That was inferred from the ABSENCE of a unique key. Reading AppointmentService.bookPublic shows
-- the model is a QUEUE, not a diary:
--
--     patients_to_visit  = the provider's capacity for the day
--     patients_appointed = THIS patient's queue number  (lastAppointed + 1)
--     date_time          = when the row was made, NOT an appointed time
--
-- So a clinic says "you are number 7 of 20 for Dr X today" — there are no minute slots to collide over, and
-- the original wording was wrong.
--
-- ── THE REAL DEFECT IS WORSE, AND IT IS RIGHT HERE ───────────────────────────────────────────────
--
--     int lastAppointed = repo.findFirst...OrderByIdDesc(...)   -- READ
--     int appointed     = lastAppointed + 1;                    -- ACT
--     if (appointed > capacity) throw ...;                      -- the capacity limit
--     repo.save(... appointed ...);
--
-- A textbook check-then-act race with NO constraint behind it. Two concurrent bookings both read 7, both
-- write 8: **two patients hold queue number 8**, and the capacity limit is breached by exactly the
-- concurrency it exists to survive. This is the same class the education review's finding D catalogued in
-- twelve dup-checks — and here, as there, the fix is the constraint, not a longer check.
--
-- ── D5: audited BEFORE constraining ──────────────────────────────────────────────────────────────
-- Checked 2026-08-07: zero duplicate (organization_id, venue_id, provider_id, date, patients_appointed)
-- tuples in the live data, so this key cannot fail the migration on an existing tenant. The education
-- programme records the opposite case — twelve dup-checks left unconstrained precisely BECAUSE a tenant
-- holding duplicates would break the deploy. Auditing first is what makes the difference.
--
-- Idempotent throughout.

-- ── 1. THE §9d FIX: one queue number per provider, per venue, per day ────────────────────────────
-- NULL patients_appointed rows (a clinic row made before this model settled) do not participate — MySQL
-- permits many NULLs in a unique index — so this constrains new bookings without rejecting old ones.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND index_name = 'uk_booking_queue') = 0,
    'ALTER TABLE booking ADD UNIQUE KEY uk_booking_queue
        (organization_id, venue_id, provider_id, date, patients_appointed)',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. SLOTS — the other booking shape, which the clinic does not have and education needs ───────
-- A parents' evening is a DIARY: "your ten minutes with Miss Khan at 18:20". That is genuinely different
-- from a queue, and forcing either into the other is how a shared core starts lying.
--
-- Recorded as a refinement of D-9: a real scheduling core supports BOTH modes. The clinic proves the queue
-- mode is not a workaround, and education proves the slot mode is not speculative.
--
-- starts_at/ends_at are REAL DATETIMEs. The clinic's `date`/`date_time` VARCHARs are left untouched and
-- unused by this table: converting them would guess at strings this slice has no business reinterpreting.
CREATE TABLE IF NOT EXISTS slot (
    slot_id         BIGINT   NOT NULL AUTO_INCREMENT,
    organization_id BIGINT   NOT NULL,
    venue_id        BIGINT   NULL,
    provider_id     BIGINT   NOT NULL,
    starts_at       DATETIME NOT NULL,
    ends_at         DATETIME NOT NULL,
    -- How many bookings this slot accepts. 1 for a parents' evening; >1 for a group session.
    capacity        INT      NOT NULL DEFAULT 1,
    -- What the slot belongs to, in the consumer's own words (a parents' evening id, a clinic session).
    -- Deliberately opaque to this service: the core schedules time, the domain knows why.
    external_ref    VARCHAR(120) NULL,
    created_at      DATETIME NULL,
    updated_at      DATETIME NULL,
    PRIMARY KEY (slot_id),
    -- ONE slot per provider per start time. The whole point of the table.
    UNIQUE KEY uk_slot_provider_time (organization_id, provider_id, starts_at),
    -- "what is open on this date" — the read every booking screen runs.
    KEY idx_slot_window (organization_id, starts_at),
    KEY idx_slot_ref (organization_id, external_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 3. A booking may belong to a slot ────────────────────────────────────────────────────────────
-- Added to the EXISTING booking table rather than creating a second one.
--
-- DESIGN CORRECTION, found in implementation: this slice's §D3 said "slot + booking", written before V3
-- renamed `appointment` to `booking` — so the table it proposed already existed under that name. One
-- booking table with a nullable slot_id is the right shape anyway: the clinic's queue rows keep slot_id
-- NULL, education's diary rows point at a slot, and both are bookings.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND column_name = 'slot_id') = 0,
    'ALTER TABLE booking ADD COLUMN slot_id BIGINT NULL AFTER organization_id',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- One booking per attendee per slot — a double-clicked Book is one booking, not two.
-- All existing rows have slot_id NULL and so do not participate (the same NULL behaviour relied on above).
SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND index_name = 'uk_booking_slot_attendee') = 0,
    'ALTER TABLE booking ADD UNIQUE KEY uk_booking_slot_attendee (organization_id, slot_id, attendee_id)',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- NO BACKFILL, and that is deliberate. The design proposed turning the 8 legacy appointments into slots
-- where their date strings parse. They are queue rows: they have no appointed time to become a slot's
-- starts_at, and the timestamps are creation times. Manufacturing slots from them would invent a diary the
-- clinic never kept — D5's "never act on inference", applied to data rather than to schema.
