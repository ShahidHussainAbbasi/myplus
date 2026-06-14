# Slice 09 — Discount (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors prior slices.
Last of the registration-section domains.

## Document — what & why

A **Discount** is a fee concession (fixed/percentage, validity window, reference) applied to students.
Org-scope it like the previous slices.

## Design

### Data
`Discount` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** → no migration.

### Repository
```java
@Query("select d from Discount d where d.organizationId = :orgId "
     + "or (d.organizationId is null and d.userId = :userId)")
List<Discount> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserDiscount` | `findScoped(org,uid)` |
| `/getUserDiscounts` (option list) | `findScoped(org,uid)` |
| `/getAllDiscount` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addDiscount` | stamps `userId` (audit) + `organizationId` (tenant); dup-name check within tenant |
| `/deleteDiscount` | by id |

## Implement (checklist)
- [x] `Discount.organizationId` column
- [x] `DiscountRepository.findScoped`
- [x] `DiscountController.orgId()`; reads/`getAllDiscount` scoped; `addDiscount` stamps org + scoped dup-check
- [x] (user) rebuild & restart education-service

## Test — all green
- [x] `addDiscount` → DB row `discount(discount_id=1, user_id=51, organization_id=1)`
- [x] scoped reads return the row; `getAllDiscount` tenant-scoped
- [x] Cypress `education/sections.cy.js` 36/36 (DiscountDiv specs green) — all 9 registration domains org-scoped
