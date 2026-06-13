# Slice 10 — Attendance (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`. First **operations** domain.

## Document — what & why

**Attendance** records student presence (denormalised enroll/student/grade + time in/out). This is an
operational/transaction domain, not a registration list. Only the **core** list/delete endpoints are
ported today; `markAttendance`/`markAttendance2` (bulk marking), `findA`, parent alerts (`sendPA`),
and `getUserStudentMap` remain **deferred**. Org-scope the reads now so they are correct the moment
marking is added.

## Design

### Data
`Attendance` gains `organization_id` (tenant scope); `user_id` kept as audit. The existing redundant
`@UniqueConstraint(columnNames = "attendance_id")` (the PK itself) is harmless — leave it.

### Repository
```java
@Query("select a from Attendance a where a.organizationId = :orgId "
     + "or (a.organizationId is null and a.userId = :userId)")
List<Attendance> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserA` | `findScoped(org,uid)` |
| `/getAllA` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/deleteA` | by id |

> No write endpoint in this controller yet (marking deferred). When `markAttendance` is built it must
> stamp `organizationId` like the registration `add*` endpoints.

## Implement (checklist)
- [x] `Attendance.organizationId` column (Hibernate added it)
- [x] `AttendanceRepository.findScoped`
- [x] `AttendanceController.orgId()`; `getUserA`/`getAllA` scoped
- [x] (user) rebuild & restart education-service

## Test — all green
- [x] scoped reads respond SUCCESS/NOT_FOUND; `getAllA` tenant-scoped; `organization_id` column present
- [x] Cypress `education/operations.cy.js` 4/4 (/getUserA, /getAllA)
