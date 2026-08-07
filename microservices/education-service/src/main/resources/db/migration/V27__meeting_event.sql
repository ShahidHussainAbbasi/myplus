-- Slice edu-3.4 / SCHED-1 B3 — guardian-teacher meetings.
-- Design: microservices/docs/slices/edu-3.4-guardian-teacher-meetings.md
--
-- ── ONE table, and note what is NOT here: the slots and the bookings ──────────────────────────────
-- Those live in the shared scheduling core (appointment-service), reached through SchedulingClient. This
-- table holds only what is EDUCATION's: the school's decision to run a parents' evening and to open or
-- close it.
--
-- That split is decision D-9 (user-chosen option B). The alternative — education owning meeting_slot and
-- meeting_booking itself — was the original 3.4 design, and it would have meant a second scheduler on a
-- platform that already had one, plus re-solving double-booking in a second place. The core now enforces
-- that with uk_slot_provider_time and uk_booking_slot_attendee, once, for every consumer.
--
-- ── How the two halves refer to each other ───────────────────────────────────────────────────────
-- Education passes `ref = 'EDU-EVT-<meeting_event_id>'` when publishing slots. The core stores it as an
-- OPAQUE string in slot.external_ref and never interprets it: providers, attendees and windows are all it
-- knows. In education's terms those are teachers, guardians and appointment times — and the core is
-- deliberately unaware of every one of those words.
--
-- ── Two states, no workflow ──────────────────────────────────────────────────────────────────────
-- OPEN accepts bookings; CLOSED does not. Same reasoning as 3.5's notices and 2.5's behaviour log: this
-- domain does not want approval chains, and a parents' evening needs exactly one boundary.
--
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS meeting_event (
    meeting_event_id BIGINT       NOT NULL AUTO_INCREMENT,
    title            VARCHAR(255) NOT NULL,
    -- The evening itself. Slot times live in the core; this is the date the school calls it.
    event_date       DATE         NULL,
    status           ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
    -- Optional guidance shown to families on the booking screen ("10 minutes per child, please arrive
    -- five minutes early").
    notes            VARCHAR(1000) NULL,
    user_id          BIGINT       NOT NULL,
    organization_id  BIGINT       NULL,
    dated            DATETIME     NULL,
    updated          DATETIME     NULL,
    PRIMARY KEY (meeting_event_id),
    -- THE portal read: "what is open for my school right now".
    KEY idx_meeting_event_open (organization_id, status, event_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
