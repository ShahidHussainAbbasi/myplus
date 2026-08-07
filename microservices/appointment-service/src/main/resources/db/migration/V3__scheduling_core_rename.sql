-- Slice SCHED-1 (B1) — rename the clinic schema into a domain-neutral scheduling core.
-- Design: microservices/docs/slices/sched-1-scheduling-core.md
--
-- ── Why ────────────────────────────────────────────────────────────────────────────────────────────
-- This service is named for a capability it does not provide. It is a CLINIC: hospital / doctor / patient,
-- and appointment.hospital_id is NOT NULL — so any other domain wanting to book time must first become a
-- hospital. Education needed guardian-teacher meetings (edu-3.4) and the only honest options were to
-- impersonate a hospital or to build a second scheduler. Decision D-9 option B, user-chosen 2026-08-07.
--
-- ── The MODEL goes neutral; the WORDS do not ──────────────────────────────────────────────────────
-- A clinic user must never see "Provider" where it said "Doctor". The domain vocabulary lives in the
-- templates and i18n bundles, which this migration does not touch, and the JSON contract is preserved by a
-- mapping layer in the service. Nothing a clinic user can observe changes.
--
-- ── D5: this table has LIVE ROWS, counted before writing this ──────────────────────────────────────
-- hospital 14 (across 2 organisations) · doctor 13 · patient 1 · appointment 6, measured 2026-08-07.
-- Every statement below therefore RENAMES IN PLACE. Nothing is copied, nothing is dropped, and there is no
-- window in which a row exists twice or not at all. A rollback is the inverse rename.
--
-- ── D9a: this is a NEW migration, never an edit to V1__baseline.sql ────────────────────────────────
-- Flyway checksums every applied script; editing the baseline would make every environment that already ran
-- it refuse to start. A fresh deploy replays V1 and then this, and lands in the same place.
--
-- ⚠ Follow-up shipped separately in V4: slot + booking + the UNIQUE keys that make double-booking
-- impossible. THIS service currently has no such key and no conflict check (programme §9d) — two patients
-- can hold the same doctor at the same minute. V3 only moves names; V4 fixes that.
--
-- Idempotent throughout, so a re-run or a dev DB that ddl-auto already touched is a no-op.

-- ── 1. hospital → venue ───────────────────────────────────────────────────────────────────────────
SET @sql := IF((SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'hospital') = 1
               AND (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'venue') = 0,
    'ALTER TABLE hospital RENAME TO venue', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. doctor → provider ──────────────────────────────────────────────────────────────────────────
-- NOTE for anyone sweeping names later (standard D9b): "Provider" already appears across this codebase as
-- AuthenticationProvider, SettingsCatalogProvider and friends. `doctor` was safe to sweep; `provider` is
-- NOT. Rename it by hand or not at all.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'doctor') = 1
               AND (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'provider') = 0,
    'ALTER TABLE doctor RENAME TO provider', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 3. patient → attendee ─────────────────────────────────────────────────────────────────────────
SET @sql := IF((SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'patient') = 1
               AND (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'attendee') = 0,
    'ALTER TABLE patient RENAME TO attendee', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 4. appointment → booking ──────────────────────────────────────────────────────────────────────
-- The row-level rename. `booking` is the neutral word for "someone has this time"; an appointment is what a
-- clinic calls one, and that word survives in the clinic's UI and its API path.
SET @sql := IF((SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'appointment') = 1
               AND (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'booking') = 0,
    'ALTER TABLE appointment RENAME TO booking', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 5. the foreign-key columns follow their tables ────────────────────────────────────────────────
-- CHANGE COLUMN rather than RENAME COLUMN (standard D9a): restating the type keeps the new definition
-- readable beside the original, and it does not require MySQL 8.0 syntax support.
--
-- The clinic's own columns — speciality, fee, patients_to_visit/appointed/visited, date, date_time — are
-- deliberately UNTOUCHED. They are clinic domain data and clinic screens still read them. A neutral core
-- does not mean erasing a vertical's fields; V4 adds real DATETIME slots beside the string ones rather
-- than converting them, because an unparseable legacy string must stay visible rather than be guessed (D5).
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND column_name = 'hospital_id') = 1,
    'ALTER TABLE booking CHANGE COLUMN hospital_id venue_id BIGINT NOT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND column_name = 'doctor_id') = 1,
    'ALTER TABLE booking CHANGE COLUMN doctor_id provider_id BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'booking'
                  AND column_name = 'patient_id') = 1,
    'ALTER TABLE booking CHANGE COLUMN patient_id attendee_id BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- provider.hospital_id → venue_id (a provider works at a venue)
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'provider'
                  AND column_name = 'hospital_id') = 1,
    'ALTER TABLE provider CHANGE COLUMN hospital_id venue_id BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- attendee.hospital_id, if the clinic recorded one
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'attendee'
                  AND column_name = 'hospital_id') = 1,
    'ALTER TABLE attendee CHANGE COLUMN hospital_id venue_id BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
