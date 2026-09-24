-- EDU-PERM-1 fix-forward: an education permission set belongs to a member of STAFF, and to nobody else.
--
-- ⚠ WHAT WENT WRONG, stated plainly, because this is the SECOND time on this platform.
--
-- V16's placement scoped by ORGANISATION TYPE — "every member of an EDUCATION org" — and a school's org
-- contains more than its staff. Two seeded accounts were swept in:
--     guardian.education@myplus.com   ROLE_GUARDIAN   a PARENT
--     student.education@myplus.com    ROLE_STUDENT    a CHILD
-- Both landed on `Teacher`, which holds marks.enter, attendance.mark, behaviour.record and student.view.
-- A parent could have entered marks; a pupil could have read every pupil's record.
--
-- This is V12 repeating. V14's header says it for the business catalogue: "V12's second INSERT read
-- 'everyone not already placed', and there are four other modules and a parent portal in this database."
-- V16 was written knowing that, cited it in its own comments, and then scoped by org type alone — which
-- is the same mistake wearing a different filter. The lesson is narrower than "remember V12": a set is
-- placed by WHAT SOMEBODY DOES, never by which organisation row they appear in.
--
-- Nothing was exploitable through a screen: guardians and students reach the portal, not the education
-- dashboard, and no education endpoint reads these codes yet (the server guards are slice 3). But it was
-- a real privilege escalation sitting in the token, and "not reachable today" is not a defence — it is
-- the definition of a latent one.
--
-- Idempotent and safe to re-run: it deletes only rows that match the portal roles, and adds nothing.

DELETE ups
  FROM user_permission_set ups
  JOIN permission_set ps ON ps.id = ups.set_id AND ps.module = 'EDUCATION'
 WHERE EXISTS (
       SELECT 1
         FROM users_roles ur
         JOIN roles r ON r.id = ur.role_id
        WHERE ur.user_id = ups.user_id
          AND r.name IN ('ROLE_GUARDIAN', 'ROLE_STUDENT'));

-- The same guard as a standing rule, for the next module that gets a catalogue: a portal account holds
-- no set at all, so AuthService mints it exactly the role privileges it minted before PERM-1 existed.
-- (There is no INSERT here on purpose — restoring a guardian to a staff set is never the right repair.)
