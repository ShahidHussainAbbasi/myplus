-- =============================================================================
-- Phase 1 migration: monolith users  ->  auth-service (single Identity Provider)
--   FROM  myplusdb.user_account, role, privilege, users_roles, roles_privileges
--   INTO  myplusdb_auth.users, users_roles
--
-- ONE-TIME script. Both schemas live on the same MySQL instance, so this is a
-- pure cross-schema copy (no ETL tool). Run it with a MySQL account that can
-- read myplusdb and write myplusdb_auth.
--
-- IDs ARE PRESERVED: every microservice business row references user_account.id
-- via a NOT NULL user_id column, so new ids would orphan all business data.
--
-- PREREQUISITES
--   1. auth-service has started at least once, so myplusdb_auth and the ported
--      role/privilege catalog (SetupDataLoader) already exist.
--   2. Take a backup of myplusdb_auth before running.
--
-- PREFLIGHT - confirm the SOURCE column names match your actual schema and edit
-- the SELECT below if they differ (the monolith uses Hibernate's default
-- camelCase physical naming; a snake_case install would use first_name, etc.):
--      DESCRIBE myplusdb.user_account;
--      DESCRIBE myplusdb_auth.users;
-- Assumed source columns: id, email, password, firstName, lastName,
--      isUsing2FA, secret, user_type, enabled.
-- =============================================================================

START TRANSACTION;

-- 1) Users — preserve id; copy BCrypt password verbatim (cost factor is self-describing);
--    map 2FA fields; synthesise a unique non-null username; skip emails already present.
INSERT INTO myplusdb_auth.users
    (id, username, email, password, first_name, last_name, phone,
     enabled, account_non_locked, failed_login_attempts, lock_time,
     two_factor_enabled, two_factor_secret, user_type, created_at, updated_at)
SELECT
    ua.id,
    CONCAT(SUBSTRING_INDEX(ua.email, '@', 1), '_', ua.id) AS username,
    ua.email,
    ua.password,
    ua.firstName,
    ua.lastName,
    NULL                              AS phone,
    ua.enabled,
    TRUE                              AS account_non_locked,
    0                                 AS failed_login_attempts,
    NULL                              AS lock_time,
    COALESCE(ua.isUsing2FA, FALSE)    AS two_factor_enabled,
    ua.secret                         AS two_factor_secret,
    ua.user_type,
    NOW(), NOW()
FROM myplusdb.user_account ua
WHERE ua.email IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM myplusdb_auth.users u WHERE u.email = ua.email);

-- 2) Role links — matched BY NAME (role ids differ between schemas). Only for users
--    that were actually migrated, and only when the target role name exists.
INSERT INTO myplusdb_auth.users_roles (user_id, role_id)
SELECT ur.user_id, ar.id
FROM myplusdb.users_roles ur
JOIN myplusdb.role        mr ON mr.id   = ur.role_id
JOIN myplusdb_auth.roles  ar ON ar.name = mr.name
JOIN myplusdb_auth.users  au ON au.id   = ur.user_id
WHERE NOT EXISTS (
    SELECT 1 FROM myplusdb_auth.users_roles x
    WHERE x.user_id = ur.user_id AND x.role_id = ar.id);

COMMIT;

-- 3) Advance AUTO_INCREMENT past the highest preserved id so new signups don't collide.
SET @next := (SELECT IFNULL(MAX(id), 0) + 1 FROM myplusdb_auth.users);
SET @ddl  := CONCAT('ALTER TABLE myplusdb_auth.users AUTO_INCREMENT = ', @next);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- =============================================================================
-- VERIFICATION (run and review; nothing below mutates data)
-- =============================================================================

-- Row counts should reconcile (target >= source minus pre-existing duplicates).
SELECT
    (SELECT COUNT(*) FROM myplusdb.user_account WHERE email IS NOT NULL) AS source_users,
    (SELECT COUNT(*) FROM myplusdb_auth.users)                          AS target_users,
    (SELECT COUNT(*) FROM myplusdb.users_roles)                         AS source_user_roles,
    (SELECT COUNT(*) FROM myplusdb_auth.users_roles)                    AS target_user_roles;

-- CRITICAL: any monolith role name with no match in auth means those users LOST that
-- role's privileges. This MUST return zero rows; if not, add the role name to
-- auth-service SetupDataLoader, restart auth-service, and re-run step 2.
SELECT DISTINCT mr.name AS unmapped_role_name
FROM myplusdb.role mr
LEFT JOIN myplusdb_auth.roles ar ON ar.name = mr.name
WHERE ar.id IS NULL;

-- Spot-check: users that ended up with no roles at all (investigate any rows).
SELECT u.id, u.email
FROM myplusdb_auth.users u
LEFT JOIN myplusdb_auth.users_roles ur ON ur.user_id = u.id
WHERE ur.user_id IS NULL;
