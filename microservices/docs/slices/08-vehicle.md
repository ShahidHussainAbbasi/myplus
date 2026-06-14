# Slice 08 — Vehicle (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors prior slices.

## Document — what & why

A **Vehicle** is a school transport (bus/van) with driver/owner contacts, linked to a school. Used by
the student transport assignment. Org-scope it like the previous slices.

## Design

### Data
`Vehicle` gains `organization_id` (tenant scope); `user_id` kept as audit. **No global unique
constraint** → no migration. `school_id` stays.

### Repository
```java
@Query("select v from Vehicle v where v.organizationId = :orgId "
     + "or (v.organizationId is null and v.userId = :userId)")
List<Vehicle> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserVehicle` | `findScoped(org,uid)` |
| `/getUserVehicles` (option list) | `findScoped(org,uid)` |
| `/getAllVehicle` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addVehicle` | stamps `userId` (audit) + `organizationId` (tenant); dup-check (number) within tenant |
| `/deleteVehicle` | by id |

## Implement (checklist)
- [x] `Vehicle.organizationId` column
- [x] `VehicleRepository.findScoped`
- [x] `VehicleController.orgId()`; reads/`getAllVehicle` scoped; `addVehicle` stamps org + scoped dup-check
- [x] (user) rebuild & restart education-service

## Test — all green
- [x] `addVehicle` → DB row `vehicle(vehicle_id=1, school_id=1, user_id=51, organization_id=1)`; `schoolName` resolved
- [x] scoped reads return the row; `getAllVehicle` tenant-scoped
- [x] Cypress `education/sections.cy.js` 36/36 (VehicleDiv specs green)
