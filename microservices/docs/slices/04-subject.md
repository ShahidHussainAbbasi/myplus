# Slice 04 — Subject (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors prior slices.

## Document — what & why

A **Subject** is a taught subject/book (name, code, publisher, edition), optionally linked to a
`Grade` (`@ManyToOne`). Org-scope it like the previous slices.

## Design

### Data
`Subject` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** → no migration. Lazy `grade` association stays; reads keep `@Transactional(readOnly=true)`.

### Repository
```java
@Query("select s from Subject s where s.organizationId = :orgId "
     + "or (s.organizationId is null and s.userId = :userId)")
List<Subject> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserSubject` | `findScoped(org,uid)` |
| `/getUserSubjects` (option list) | `findScoped(org,uid)` |
| `/getAllSubject` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addSubject` | stamps `userId` (audit) + `organizationId` (tenant); dup-name check within tenant |
| `/deleteSubject` | by id |

## Implement (checklist)
- [x] `Subject.organizationId` column
- [x] `SubjectRepository.findScoped`
- [x] `SubjectController.orgId()`; reads/`getAllSubject` scoped; `addSubject` stamps org + scoped dup-check
- [x] compile education-service

## Test — all green
- [x] education-service compiles & boots (Hibernate added `organization_id`)
- [x] `addSubject` → DB row `subject(subject_id=1, grade_id=1, user_id=51, organization_id=1)`; `gradeName` resolved
- [x] scoped reads return the row; `getAllSubject` tenant-scoped
- [x] Cypress `education/sections.cy.js` 36/36 (SubjectDiv specs green)
