-- Slice 2.5 follow-up — rename behaviour_note.parent_informed to guardian_informed.
--
-- WHY A NEW SCRIPT AND NOT AN EDIT TO V21:
-- V21 is already applied in every environment that has run 2.5. Editing an applied script changes its
-- checksum and Flyway refuses to start — correctly. A rename is a schema CHANGE, so it ships as a schema
-- change, and a fresh deploy replays V21 (creating parent_informed) then V23 (renaming it) to the same
-- end state as an existing one. That is the whole point of the standard.
--
-- WHY THE RENAME AT ALL:
-- The domain calls this person a GUARDIAN everywhere else — Guardian, GuardianController,
-- guardian_portal_access, the portal itself. "Parent" was the odd one out, and it is also wrong more often
-- than it is right: the adult a school informs about a behaviour note is frequently a grandparent, an
-- older sibling, a foster carer or a local authority. The column recorded a fact about the guardian of
-- record, not about a parent.
--
-- CHANGE COLUMN (not RENAME COLUMN) because it works on MySQL 5.7 as well as 8.0, and it restates the
-- type, which keeps the definition readable next to V21's.

ALTER TABLE behaviour_note
    CHANGE COLUMN parent_informed    guardian_informed    TINYINT(1) NOT NULL DEFAULT 0,
    CHANGE COLUMN parent_informed_on guardian_informed_on DATE       NULL;
