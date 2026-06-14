# Slice 05 — Staff (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors prior slices.

## Document — what & why

**Staff** are teachers/employees of a school, each linked (EAGER `@ManyToMany`) to the grades they
teach. Feeds attendance and class-assignment. Org-scope it like the previous slices.

## Design

### Data
`Staff` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** → no migration. EAGER `grades` association unchanged.

### Repository
```java
@Query("select s from Staff s where s.organizationId = :orgId "
     + "or (s.organizationId is null and s.userId = :userId)")
List<Staff> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserStaff` | `findScoped(org,uid)` |
| `/getUserStaffs` (option list) | `findScoped(org,uid)` |
| `/getAllStaff` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addStaff` | stamps `userId` (audit) + `organizationId` (tenant); dup-name check within tenant |
| `/deleteStaff` | by id |

## Implement (checklist)
- [x] `Staff.organizationId` column
- [x] `StaffRepository.findScoped`
- [x] `StaffController.orgId()`; reads/`getAllStaff` scoped; `addStaff` stamps org + scoped dup-check
- [x] compile education-service

## Test — all green
- [x] education-service compiles & boots (Hibernate added `organization_id`)
- [x] `addStaff` → DB row `staff(staff_id=1, user_id=51, organization_id=1)`; grades linked (`gradeNames:["Grade 1"]`)
- [x] scoped reads return the row; `getAllStaff` tenant-scoped
- [x] Cypress `education/sections.cy.js` 36/36 (StaffDiv specs green)
