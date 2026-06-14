# Slice 06 — Guardian (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors prior slices.

## Document — what & why

A **Guardian** is a student's parent/guardian (contact, CNIC, relation). Feeds the student form and
fee/alert flows. Org-scope it like the previous slices. (CSV import endpoints stay deferred.)

## Design

### Data
`Guardian` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** → no migration.

### Repository
```java
@Query("select g from Guardian g where g.organizationId = :orgId "
     + "or (g.organizationId is null and g.userId = :userId)")
List<Guardian> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserGuardian` | `findScoped(org,uid)` |
| `/getUserGuardians` (option list) | `findScoped(org,uid)` |
| `/getAllGuardian` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addGuardian` | stamps `userId` (audit) + `organizationId` (tenant); dup-check (name+CNIC) within tenant |
| `/deleteGuardian` | by id (returns GenericResponse) |

## Implement (checklist)
- [x] `Guardian.organizationId` column
- [x] `GuardianRepository.findScoped`
- [x] `GuardianController.orgId()`; reads/`getAllGuardian` scoped; `addGuardian` stamps org + scoped dup-check
- [x] (user) rebuild & restart education-service

## Test — all green
- [x] `addGuardian` → DB row `guardian(guardian_id=1, user_id=51, organization_id=1)`
- [x] scoped reads return the row; `getAllGuardian` tenant-scoped
- [x] Cypress `education/sections.cy.js` 36/36 (GuardianDiv specs green)
