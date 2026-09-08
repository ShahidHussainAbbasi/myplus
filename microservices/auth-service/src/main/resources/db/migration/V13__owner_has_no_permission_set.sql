-- PERM-1 fix-forward: an OWNER is not on a permission set.
--
-- V12's design says an owner's access is implicit, precisely so that no edit to any set can lock them
-- out of their own shop. The SQL did not do it: the first INSERT placed admins on Administrator and the
-- second placed "everyone not already placed" on Standard -- which swept owners up with everybody else.
--
-- Nothing was BROKEN by that. AuthService mints an owner's token from permissionService.everything(),
-- ignoring their set row entirely, so an owner has held every permission throughout. The damage was to
-- the SCREEN: the team table shows a permission-set picker per member, and on an owner's row it offered
-- a control that appears to change their access and cannot. A control that does nothing is worse than
-- no control -- it invites the one action it will silently ignore.
--
-- V12 is already applied and its checksum is fixed, so this is a separate migration rather than an edit.
DELETE ups FROM user_permission_set ups
  JOIN users_roles ur ON ur.user_id = ups.user_id
  JOIN roles r        ON r.id = ur.role_id
 WHERE r.name = 'ROLE_OWNER';
