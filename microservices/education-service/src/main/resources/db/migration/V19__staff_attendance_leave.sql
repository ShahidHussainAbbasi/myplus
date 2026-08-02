-- Slice 2.3 — staff attendance & leave.
-- Design: microservices/docs/slices/edu-2.3-staff-attendance-leave.md
--
-- Three tables answering two questions that meet at one point:
--     register → "Mrs Khan is not here"      ─┐
--                                              ├─→ staff_absence (2.2) → substitution
--     leave    → "Mrs Khan is approved off"  ─┘
-- Both paths write 2.2's staff_absence. That convergence IS the slice: if either forgets, a teacher is on
-- approved leave with no cover arranged and nothing looks wrong.
--
-- ── The UNIQUE key the student register never got ──────────────────────────────────────────────────
-- `attendance` (students) has NO unique key on (organization_id, enroll_no, att_date); it upserts via
-- findFirstBy…, a check-then-act race — two concurrent saves of one register create two rows. Same defect
-- finding D found in twelve duplicate checks. staff_attendance carries the constraint from day one.
-- (The student-side gap is recorded in the slice §6 and the programme's carried-requirements table; fixing
-- it on a live table needs the audit-first discipline of DB standard D5.)
--
-- ── No balance column, anywhere ────────────────────────────────────────────────────────────────────
-- A leave balance is quota − approved days taken, DERIVED on read. A stored balance is a cache of a sum
-- that goes wrong the moment a request is cancelled or back-dated, with nothing saying so — and it is the
-- number a teacher will argue about. 1.4 D4's rule (grading is derived) applied to leave.
-- leave_request.days_counted is NOT a balance: it records what a specific decision granted, after
-- non-session days were skipped, so the arithmetic cannot silently change when the term calendar does.
--
-- Enums are MySQL enum columns against @Enumerated(STRING): a new value later needs ALTER … MODIFY.
-- Indexes follow standard D3b — index the query, not just the scope.
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS staff_attendance (
    staff_attendance_id BIGINT       NOT NULL AUTO_INCREMENT,
    staff_id            BIGINT       NOT NULL,
    staff_name          VARCHAR(255) NULL,
    att_date            DATE         NOT NULL,
    status              ENUM('PRESENT','ABSENT','LATE','HALF_DAY','LEAVE') NOT NULL DEFAULT 'PRESENT',
    time_in             TIME         NULL,
    time_out            TIME         NULL,
    remarks             VARCHAR(255) NULL,
    user_id             BIGINT       NOT NULL,
    organization_id     BIGINT       NULL,
    dated               DATETIME     NULL,
    updated             DATETIME     NULL,
    PRIMARY KEY (staff_attendance_id),
    -- the constraint student attendance lacks: one row per person per day, enforced by the DATABASE
    UNIQUE KEY uk_staff_attendance_day (organization_id, staff_id, att_date),
    -- "today's register" — the query the screen opens with
    KEY idx_staff_att_org_date (organization_id, att_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS leave_type (
    leave_type_id     BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(255) NOT NULL,
    -- NULL = uncapped; unpaid leave usually has no quota
    annual_quota      INT          NULL,
    -- nothing here acts on this; Phase 4 payroll needs the fact and should not have to backfill it
    paid              TINYINT(1)   NOT NULL DEFAULT 1,
    sequence          INT          NULL,
    user_id           BIGINT       NOT NULL,
    organization_id   BIGINT       NULL,
    dated             DATETIME     NULL,
    updated           DATETIME     NULL,
    PRIMARY KEY (leave_type_id),
    -- one "Casual" per school; case-insensitive via the column collation (standard D3c)
    UNIQUE KEY uk_leave_type_name (organization_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS leave_request (
    leave_request_id   BIGINT       NOT NULL AUTO_INCREMENT,
    staff_id           BIGINT       NOT NULL,
    staff_name         VARCHAR(255) NULL,
    leave_type_id      BIGINT       NOT NULL,
    -- snapshotted: renaming a leave type must not retitle a decision already taken (1.5 D1's rule)
    leave_type_name    VARCHAR(255) NULL,
    from_date          DATE         NOT NULL,
    to_date            DATE         NOT NULL,
    -- what this decision actually granted, after non-session days were skipped (D4). NOT a balance.
    days_counted       INT          NULL,
    status             ENUM('PENDING','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    reason             VARCHAR(500) NULL,
    decided_by_user_id BIGINT       NULL,
    decided_on         DATETIME     NULL,
    user_id            BIGINT       NOT NULL,
    organization_id    BIGINT       NULL,
    dated              DATETIME     NULL,
    updated            DATETIME     NULL,
    PRIMARY KEY (leave_request_id),
    -- the pending queue, and one teacher's history
    KEY idx_leave_req_org_status (organization_id, status),
    KEY idx_leave_req_org_staff (organization_id, staff_id, from_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
