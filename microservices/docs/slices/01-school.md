# Slice 01 — School (education-service)

Status: **DONE** ✅ (first vertical slice; reference implementation of the multi-tenant standard).
Follows `../ARCHITECTURE-MULTITENANCY.md`.

## Document — what & why

The **School** is the anchor of the School Management System: an owner creates one or more school
branches; everything else (grades, staff, students, fees) hangs off a school. It is the natural first
slice because a school maps cleanly onto a tenant/organization, so getting it right establishes the
org-scoping pattern every later domain copies.

Goal of this slice: the School domain works **100% end-to-end** — the monolith `educationDashboard`
School section, the flat endpoints, the service, and **tenant-scoped** persistence — with Cypress green.

## Design

### Data
`School` entity (`education-service`) gains:
- `organization_id BIGINT NULL` — tenant scope (the new field).
- keeps `user_id` — now **audit only** (who created the branch).

No UI/DTO change: `organizationId` is server-side only; the monolith never sees or sends it.

### Repository
```java
@Query("select s from School s where s.organizationId = :orgId "
     + "or (s.organizationId is null and s.userId = :userId)")
List<School> findScoped(Long orgId, Long userId);   // org scope + own NULL-org rows (migration)
```
Existing `findByUserId` retained (not used by the flat path anymore).

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Method | Scope change |
|----------|--------|--------------|
| `/getMainBranchName` | GET | `findScoped(org,uid)` |
| `/getUserSchool` | GET | `findScoped(org,uid)` → list of `SchoolDTO` |
| `/getUserSchools` | GET | `findScoped(org,uid)` → `<option>` list |
| `/getAllSchool` | GET | **was** `findAll()` (cross-tenant leak) → now `findScoped(org,uid)` |
| `/addSchool` | POST | stamps `userId` (audit) + `organizationId` (tenant); dup-branch check within tenant |
| `/deleteSchool` | POST | by id (ids already belong to the tenant via the listing) |

Identity/tenant come from `AuthenticatedUser`: `userId()` and new `orgId()` helpers in the controller.

### Security note
`/getAllSchool` previously returned every tenant's schools. Scoping it to the active org closes a
tenant-isolation hole; for a single-owner (one org) the result is unchanged.

## Implement (checklist)
- [x] `School.organizationId` column
- [x] `SchoolRepository.findScoped`
- [x] `SchoolController.orgId()` helper
- [x] reads use `findScoped`; `getAllSchool` scoped; `addSchool` stamps `organizationId`; dup-check scoped
- [x] compile education-service

## Test — all green
- [x] education-service compiles & boots
- [x] JWT carries `activeOrgId:1`; gateway stamps `X-Org-Id`; principal exposes `organizationId`
- [x] `addSchool` → DB row `school(school_id=1, user_id=51, organization_id=1)` (org stamped, not just NULL-fallback)
- [x] `getUserSchool` / `getAllSchool` / `getUserSchools` scoped reads return the row
- [x] Cypress `education/sections.cy.js` SchoolDiv specs green; full education suite 42/42 passing
