-- EDU-PERM-1 slice 3, precondition: widen `Front Office` to the work a school office actually does.
--
-- ⚠ FOUND BY MAPPING THE ENDPOINTS TO THE CODES BEFORE WRITING A SINGLE GUARD, which is the only way this
-- was ever going to surface. Cross-referencing all 157 mappable education endpoints against the five
-- built-in sets showed eleven codes that NO staff set holds — meaning that the moment those endpoints are
-- guarded, every role except Principal loses them.
--
-- Six of the eleven are correct and deliberate: staff.create, staff.delete, school.edit, settings.edit,
-- student.delete and reportcard.publish are the head's own acts, and reportcard.publish being restricted is
-- the separation this whole slice exists to express.
--
-- Four are simply wrong, and would have made the school stop working:
--     timetable.edit        5 endpoints   building the timetable
--     timetable.substitute  4 endpoints   arranging cover for an absent teacher
--     attendance.staff      6 endpoints   staff attendance, leave types, leave requests
--     exam.edit             8 endpoints   setting up examinations and grading
-- In every mainstream school system these are FRONT OFFICE work, not the head's: the office timetables,
-- the office arranges cover when a teacher calls in sick, the office keeps the leave register. Leaving them
-- Principal-only would mean the head personally arranges every substitution.
--
-- The eleventh, settings.view, is NOT fixed here because it is not a permissions problem: /getConfig is read
-- at PAGE LOAD by every education screen (P7.3 reads the same catalogue to apply the keyboard policies), so
-- it is infrastructure and must not be gated on a code at all. Recorded in the slice-3 map.
--
-- Additive and idempotent: it grants, never revokes, and re-running inserts nothing.

INSERT INTO permission_set_item (set_id, permission_code)
SELECT ps.id, src.code FROM (
  SELECT 'timetable.edit' code       UNION ALL
  SELECT 'timetable.substitute'      UNION ALL
  SELECT 'attendance.staff'          UNION ALL
  SELECT 'exam.edit'
) src
JOIN permission_set ps
  ON ps.organization_id IS NULL AND ps.name = 'Front Office' AND ps.module = 'EDUCATION'
WHERE NOT EXISTS (
  SELECT 1 FROM permission_set_item i WHERE i.set_id = ps.id AND i.permission_code = src.code);

-- CLOSE it again: each of those implies views the office cannot work without (timetable.edit implies
-- class.view and subject.view; attendance.staff implies attendance.view and staff.view; exam.edit implies
-- exam.view). Same one-pass closure as V16 section 4b, and DISTINCT for the same reason -- two of these
-- imply the same view, and NOT EXISTS cannot see rows the statement is itself producing.
INSERT INTO permission_set_item (set_id, permission_code)
SELECT DISTINCT i.set_id, p2.code
  FROM permission_set_item i
  JOIN permission_set ps ON ps.id = i.set_id
                        AND ps.module = 'EDUCATION'
                        AND ps.organization_id IS NULL
                        AND ps.name = 'Front Office'
  JOIN permission p  ON p.code = i.permission_code AND p.implies IS NOT NULL
  JOIN permission p2 ON FIND_IN_SET(p2.code, p.implies) > 0
 WHERE NOT EXISTS (
   SELECT 1 FROM permission_set_item x WHERE x.set_id = i.set_id AND x.permission_code = p2.code);
