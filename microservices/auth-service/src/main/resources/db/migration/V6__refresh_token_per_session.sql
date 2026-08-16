-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- A refresh token is a SESSION, not a user.
--
-- `refresh_tokens` carried `UNIQUE KEY (user_id)` (Hibernate-generated from an @OneToOne), so a user
-- could hold exactly ONE refresh token. Every login overwrote that single row, which silently killed
-- the ability of every OTHER logged-in device to refresh: once its 15-minute access token expired, the
-- older session got "Invalid refresh token" and every downstream call 401'd.
--
-- For a POS/SaaS platform this is a live product defect, not a theoretical one — a shop owner signed in
-- at the till and on a phone is the normal case, as is a back-office tab beside a counter. Whichever
-- device logged in FIRST broke ~15 minutes later, and (because the monolith swallowed the refusal) it
-- broke as an unexplained error rather than an honest "please sign in again".
--
-- Industry standard is one row per session/device with the user as a plain FK. This migration removes
-- the unique constraint and leaves an ordinary index in its place.
--
-- ORDER MATTERS: the FK on user_id needs an index. MySQL refuses to drop the last index covering a FK
-- column ("Cannot drop index … needed in a foreign key constraint"), so the replacement index is
-- created FIRST and the unique one dropped after.
--
-- Both steps are written against information_schema rather than hard-coded names, because the unique
-- key was named by Hibernate (`UK7tdcd6ab5wsgoudnvj7xf1b7l` on the machine this was found on) and that
-- name is not guaranteed to be identical in another environment. Re-running is harmless.
-- ─────────────────────────────────────────────────────────────────────────────────────────────────

-- 1. Ensure a NON-unique index on user_id exists to carry the foreign key.
SET @has_plain_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'refresh_tokens'
      AND INDEX_NAME   = 'idx_refresh_tokens_user'
);
SET @sql := IF(@has_plain_idx > 0,
               'SELECT 1',
               'CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Drop whatever UNIQUE index sits on user_id (single-column only — never touch the unique on
--    `token`, which must stay: a token string has to identify exactly one session).
SET @uniq_idx := (
    SELECT s.INDEX_NAME
    FROM information_schema.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME   = 'refresh_tokens'
      AND s.COLUMN_NAME  = 'user_id'
      AND s.NON_UNIQUE   = 0
      AND (SELECT COUNT(*) FROM information_schema.STATISTICS c
           WHERE c.TABLE_SCHEMA = s.TABLE_SCHEMA
             AND c.TABLE_NAME   = s.TABLE_NAME
             AND c.INDEX_NAME   = s.INDEX_NAME) = 1
    LIMIT 1
);
SET @sql := IF(@uniq_idx IS NULL,
               'SELECT 1',
               CONCAT('ALTER TABLE refresh_tokens DROP INDEX `', @uniq_idx, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
