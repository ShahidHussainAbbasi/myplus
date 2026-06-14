# Slice 03 — Grade (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors `01-school.md` / `02-owner.md`.

## Document — what & why

A **Grade** is a class/section within a school (`school_id`), with code, room, fee, and timing. It
feeds the student-enrolment and fee flows. Org-scope it like the previous slices.

## Design

### Data
`Grade` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** on the table → no migration needed this slice. `school_id` stays (intra-tenant FK).

### Repository
```java
@Query("select g from Grade g where g.organizationId = :orgId "
     + "or (g.organizationId is null and g.userId = :userId)")
List<Grade> findScoped(Long orgId, Long userId);
```
Existing `findBySchoolId` retained.

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserGrade` | `findScoped(org,uid)` |
| `/getUserGrades` (option list) | `findScoped(org,uid)` |
| `/getAllGrade` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addGrade` | stamps `userId` (audit) + `organizationId` (tenant); dup-check (name+schoolId) within tenant |
| `/deleteGrade` | by id |

## Implement (checklist)
- [x] `Grade.organizationId` column
- [x] `GradeRepository.findScoped`
- [x] `GradeController.orgId()`; reads/`getAllGrade` scoped; `addGrade` stamps org + scoped dup-check
- [x] compile education-service

## Test — all green
- [x] education-service compiles & boots (Hibernate added `organization_id`)
- [x] `addGrade` → DB row `grade(grade_id=1, school_id=1, user_id=51, organization_id=1)`; `schoolName` resolved
- [x] scoped reads return the row; `getAllGrade` tenant-scoped
- [x] Cypress `education/sections.cy.js` 36/36 (GradeDiv specs green)
