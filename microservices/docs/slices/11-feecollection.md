# Slice 11 — Fee Collection (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`. Second **operations** domain.

## Document — what & why

**Fee Collection** records a fee payment against a student (enroll no, fee/paid/dues, payment date,
receiver). Core list/add/delete are ported; `loadFV` (voucher), `loadFL`/`loadFR` (ledger/receipt) and
`findFc` remain **deferred**. Unlike Attendance, this controller has a write path (`addFc`) that must
stamp `organizationId`.

## Design

### Data
`FeeCollection` gains `organization_id` (tenant scope); `user_id` kept as audit. Redundant
`@UniqueConstraint(columnNames = "fc_id")` (the PK) is harmless — leave it.

### Repository
```java
@Query("select f from FeeCollection f where f.organizationId = :orgId "
     + "or (f.organizationId is null and f.userId = :userId)")
List<FeeCollection> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserFc` | `findScoped(org,uid)` |
| `/getAllFc` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addFc` | stamps `userId` (audit) + `organizationId` (tenant) |
| `/deleteFc` | by id |

## Implement (checklist)
- [x] `FeeCollection.organizationId` column
- [x] `FeeCollectionRepository.findScoped`
- [x] `FeeCollectionController.orgId()`; `getUserFc`/`getAllFc` scoped; `addFc` stamps org
- [x] (user) rebuild & restart education-service

## Test — all green
- [x] `addFc` → DB row `fee_collection(fc_id=1, enroll_no=ENR-001, user_id=51, organization_id=1)`
- [x] scoped reads return the row; `getAllFc` tenant-scoped
- [x] Cypress `education/operations.cy.js` 4/4 (/getUserFc, /getAllFc)
