-- R4 fix — plan_guarantor.role becomes VARCHAR(16), matching every other status/role column here.
--
-- WHAT BROKE
-- V56 declared `role ENUM('GUARANTOR','WITNESS')` while PlanGuarantor maps it as a plain String. Hibernate
-- runs with ddl-auto=validate, expected varchar(16), found enum, and REFUSED TO BOOT:
--
--   Schema-validation: wrong column type encountered in column [role] in table [plan_guarantor];
--   found [enum (Types#CHAR)], but expecting [varchar(16) (Types#VARCHAR)]
--
-- business-service then crash-looped 9 times, and every screen that needs it answered
-- `200 {"status":"ERROR"}` — which the Cypress login helper reported as a dead downstream token, a symptom
-- three layers away from the cause. Exactly the shape of the ONB-3 failure a day earlier (@Lob mapping to
-- CLOB/tinytext against a TEXT column), and of education-service's Notice.body before that.
--
-- THE RULE THIS RESTATES, for the third time
-- A column type is part of the ENTITY CONTRACT, not a free choice in the migration. Under `validate`, an
-- entity that says String and DDL that says ENUM is not a mismatch Hibernate tolerates — it is a service
-- that does not start. VARCHAR is what installment_plan.status and serial_unit.status already use for
-- exactly this kind of value, and matching them is the whole fix.
--
-- WHY A NEW MIGRATION RATHER THAN EDITING V56
-- V56 has already run — this schema is at 57 — so its checksum is recorded in flyway_schema_history. Editing
-- an applied migration makes Flyway refuse to start in every environment that already has it, which turns a
-- one-service outage into all of them. A fresh deploy runs V56 then V58 and lands in the same place.
--
-- Idempotent per D7: re-running against an already-VARCHAR column is a no-op rather than an error.

SET @needs_alter := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name   = 'plan_guarantor'
      AND column_name  = 'role'
      AND data_type    = 'enum'
);

SET @sql := IF(@needs_alter > 0,
    'ALTER TABLE plan_guarantor MODIFY COLUMN role VARCHAR(16) NOT NULL DEFAULT ''GUARANTOR''',
    'DO 0');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
