# Slice 07 — Student (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors prior slices.

## Document — what & why

A **Student** is the central SMS entity — enrolment, fee mode/discount, and links to school, grade,
guardian, vehicle. Org-scope it like the previous slices. CSV/Excel import (importCSV/impStudents,
getUserStudentMap) stays **deferred** to a focused follow-up.

## Design

### Data
`Student` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** → no migration. The `school_id`/`grade_id`/`guardian_id` columns stay (intra-tenant FKs).

### Repository
```java
@Query("select s from Student s where s.organizationId = :orgId "
     + "or (s.organizationId is null and s.userId = :userId)")
List<Student> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserStudent` | `findScoped(org,uid)` |
| `/getUserStudents` (option list) | `findScoped(org,uid)` |
| `/getAllStudent` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addStudent` | stamps `userId` (audit) + `organizationId` (tenant); dup-check (enrollNo) within tenant |
| `/deleteStudent` | by id |

## Implement (checklist)
- [x] `Student.organizationId` column
- [x] `StudentRepository.findScoped`
- [x] `StudentController.orgId()`; reads/`getAllStudent` scoped; `addStudent` stamps org + scoped dup-check
- [x] (user) rebuild & restart education-service

## Test — all green
- [x] `addStudent` → DB row `student(student_id=1, enroll_no=ENR-001, school_id=1, grade_id=1, guardian_id=1, user_id=51, organization_id=1)`
- [x] scoped reads return the row; `getAllStudent` tenant-scoped; names resolved (school "Main", guardian "Mr Khan", grade "Grade 1")
- [x] Cypress `sections.cy.js` 36/36 (StudentDiv) + `dashboard.cy.js` 2/2 green
