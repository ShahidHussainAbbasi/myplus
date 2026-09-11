-- OPTIMISTIC LOCKING ON CUSTOMER — stop two tills silently overwriting each other.
--
-- WHAT HAPPENS TODAY
-- Two cashiers open the same customer. The first corrects the phone number and saves; the second, holding
-- a copy loaded before that, saves an address change. The second write carries the OLD phone number and
-- puts it back. Nothing errors, nothing logs, and both screens look correct until somebody reloads. It is
-- last-write-wins with no way to know a write was lost.
--
-- That is live behaviour, not a new risk — but it becomes far easier to hit the moment saving is fast and
-- non-blocking (PERF-13), because the window between "load" and "save" is exactly where people now work.
--
-- WHY A VERSION COLUMN AND NOT A TIMESTAMP COMPARISON
-- `updated` already exists and is tempting. It is the wrong tool: it has second (not millisecond) fidelity
-- in places, it is set BY the save being validated, and two writes inside the same second compare equal.
-- A monotonic counter that JPA owns has none of those ambiguities — Hibernate increments it in the same
-- UPDATE, and the row count tells it whether anyone else got there first.
--
-- ⚠ DEFAULT 0 AND NOT NULL, AND THAT COMBINATION IS DELIBERATE
-- `ddl-auto=validate` means the column must exist and match `@Version private Long version` exactly, or
-- business-service will not start. All 3,383 existing customers need a value: NULL would make Hibernate
-- treat every pre-existing row as TRANSIENT and try to INSERT it on the next save. So the DEFAULT is not a
-- convenience, it is what keeps every row already in the table updatable.
--
-- BIGINT rather than INT: a row edited a hundred times a day for twenty years is still nowhere near
-- overflowing either, but the entity field is Long and validate compares types.

ALTER TABLE customer
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
