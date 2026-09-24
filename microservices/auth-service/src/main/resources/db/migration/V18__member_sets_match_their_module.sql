-- EDU-PERM-1 fix-forward #2: a member sits on a set from THEIR OWN module, or on none.
--
-- ⚠ THE SAME DEFECT, BY A THIRD ROAD. Worth listing all three together, because the lesson only becomes
-- usable once you see that "remember V12" has now failed twice:
--
--   V12  placed sets by "everyone not already placed"        -> a ROLE_GUARDIAN held sale.create
--   V16  placed sets by ORGANISATION TYPE                    -> a parent and a pupil held marks.enter
--   createOrgUser placed sets by a hardcoded set NAME        -> THIS ONE
--
-- AuthService.createOrgUser asked for "Administrator" or "Standard" — both BUSINESS built-ins — and
-- PermissionService.setsFor(orgId) returns every built-in whatever its module, because built-ins carry
-- organization_id IS NULL. So every teacher created in a SCHOOL was placed on the shop's `Standard` set:
--     customer.* product.* purchase.* sale.create sale.discount sale.edit stock.* supplier.* till.*
-- Twenty-three shop codes, minted into a school token. Five accounts were found in that state.
--
-- The rule, stated so it survives the next module: place a member by WHAT THEY DO IN THEIR OWN MODULE.
-- Not by the organisation row they appear in, not by a set name that happens to exist, not by "everyone
-- left over". The code guard now lives in PermissionService.assign() where no caller can route around it;
-- this migration repairs the rows that were written before it existed.
--
-- ⚠ PHARMA IS NOT A MISMATCH. A dispensing counter reads the BUSINESS catalogue deliberately (V15 placed
-- its members on the business built-ins, and PermissionService.moduleOf maps PHARMA -> BUSINESS). So the
-- test is against the tenant's MODULE, never its org type — `o.type <> ps.module` would wrongly sweep
-- every pharmacy member into this repair.
--
-- Idempotent: it moves only rows that are still mismatched, and re-running finds none.

-- 1 · a school member on a foreign set moves to `Teacher`, which holds exactly the nine screens that
--     carry no guard today — so nobody loses what they can reach this morning.
UPDATE user_permission_set ups
  JOIN users u            ON u.id = ups.user_id
  JOIN memberships m      ON m.user_id = u.id
  JOIN organizations o    ON o.id = m.organization_id AND o.type = 'EDUCATION'
  JOIN permission_set cur ON cur.id = ups.set_id
   SET ups.set_id = (SELECT id FROM permission_set
                      WHERE organization_id IS NULL AND name = 'Teacher' AND module = 'EDUCATION')
 WHERE cur.module <> 'EDUCATION';

-- 2 · and a school ADMIN moves to `Principal` rather than Teacher, so an admin keeps what they had.
UPDATE user_permission_set ups
  JOIN users u             ON u.id = ups.user_id
  JOIN memberships m       ON m.user_id = u.id
  JOIN organizations o     ON o.id = m.organization_id AND o.type = 'EDUCATION'
  JOIN users_roles ur      ON ur.user_id = u.id
  JOIN roles r             ON r.id = ur.role_id
  JOIN roles_privileges rp ON rp.role_id = r.id
  JOIN privileges pv       ON pv.id = rp.privilege_id AND pv.name = 'ADMIN_PRIVILEGE'
   SET ups.set_id = (SELECT id FROM permission_set
                      WHERE organization_id IS NULL AND name = 'Principal' AND module = 'EDUCATION')
 WHERE ups.set_id = (SELECT id FROM permission_set
                      WHERE organization_id IS NULL AND name = 'Teacher' AND module = 'EDUCATION');
