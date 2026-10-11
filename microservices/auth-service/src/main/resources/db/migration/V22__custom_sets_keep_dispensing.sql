-- B2 (release readiness 2026-10-11) — nobody who could dispense yesterday is refused today.
--
-- V21 introduced rx.dispense and pharma now requires it to dispense, change or cancel a prescription. V21 granted it
-- to the BUILT-IN Standard and Administrator only. A business's OWN set (a copy the owner edited) could not have
-- carried it — the permission did not exist — yet its members dispensed every day, because dispensing needed only
-- the role's write privilege. Without this, they are refused the moment pharma is deployed.
--
-- So every business custom set that EXISTED BEFORE V21 gets rx.dispense: exactly yesterday's behaviour. A set an
-- owner creates after V21 is their deliberate choice (the screen shows the permission) and is left alone. The owner
-- can still take it off any set afterwards. Idempotent: NOT EXISTS, and safe to run on a database where V21 and
-- V22 run in the same migrate (every existing custom set is then older than V21).
--
-- Members on NO set are handled in AuthService.buildClaims (they hold role privileges only — see B2 there).

INSERT INTO permission_set_item (set_id, permission_code)
SELECT s.id, 'rx.dispense'
  FROM permission_set s
 WHERE s.organization_id IS NOT NULL
   AND s.is_builtin = 0
   AND s.module = 'BUSINESS'
   AND (s.created_at IS NULL
        OR s.created_at < (SELECT h.installed_on FROM flyway_schema_history h WHERE h.version = '21' AND h.success = 1))
   AND EXISTS (SELECT 1 FROM permission WHERE code = 'rx.dispense')
   AND NOT EXISTS (SELECT 1 FROM permission_set_item i WHERE i.set_id = s.id AND i.permission_code = 'rx.dispense');
