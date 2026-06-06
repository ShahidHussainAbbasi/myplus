# Slice 02 — Owner (education-service)

Status: **DONE** ✅. Follows `../ARCHITECTURE-MULTITENANCY.md`; mirrors `01-school.md`.

## Document — what & why

An **Owner** is a school proprietor/director. Schools reference owners (`schools_owners` join), so the
owner list feeds the School form's owner picker. Same org-scoping treatment as School, plus a
tenancy-correctness fix on the uniqueness rule.

## Design

### Data
`Owner` entity gains `organization_id` (tenant scope); `user_id` kept as audit.

**Uniqueness fix (multi-tenant bug):** today `@Table(uniqueConstraints = @UniqueConstraint(columnNames
= "name"))` makes owner names globally unique — so one tenant's "John" blocks every other tenant.
Change to **per-tenant**: `UNIQUE(organization_id, name)`.

`ddl-auto: update` will NOT drop the existing single-column unique index, so this slice includes a
one-time migration:
```sql
-- drop the old global unique on name (index name from SHOW INDEX), then add the composite
ALTER TABLE owner DROP INDEX <old_name_unique>;
ALTER TABLE owner ADD CONSTRAINT uq_owner_org_name UNIQUE (organization_id, name);
```

### Repository
```java
@Query("select o from Owner o where o.organizationId = :orgId "
     + "or (o.organizationId is null and o.userId = :userId)")
List<Owner> findScoped(Long orgId, Long userId);
```

### Endpoints (flat, GenericResponse) — contract unchanged for the monolith
| Endpoint | Scope change |
|----------|--------------|
| `/getUserOwner` | `findScoped(org,uid)` |
| `/getUserOwners` (option list) | `findScoped(org,uid)` |
| `/getAllOwner` | **was** `findAll()` (cross-tenant leak) → `findScoped(org,uid)` |
| `/addOwner` | stamps `userId` (audit) + `organizationId` (tenant); dup-name check within tenant |
| `/deleteOwner` | by id |

## Implement (checklist)
- [x] `Owner.organizationId` column + composite unique `(organization_id, name)` (Hibernate created `uq_owner_org_name`)
- [x] migration: drop old `UNIQUE(name)` index `UKjryjfrl809i37qthat5rwpnq5` (Hibernate `update` won't drop it)
- [x] `OwnerRepository.findScoped`
- [x] `OwnerController.orgId()`; reads/`getAllOwner` scoped; `addOwner` stamps org + scoped dup-check
- [x] compile education-service

## Test — all green
- [x] education-service compiles & boots; Hibernate added `organization_id` + composite unique
- [x] `addOwner` → DB row `owner(owner_id=1, user_id=51, organization_id=1)` (org stamped)
- [x] `getUserOwner` / `getAllOwner` scoped reads return the row
- [x] migration applied: `owner` indexes now `PRIMARY` + `uq_owner_org_name(organization_id,name)` only
- [x] Cypress `education/sections.cy.js` 36/36 (OwnerDiv specs green)

## Migration SQL (run once per environment, see `../migrations.md`)
```sql
-- after the service boots once (so organization_id + uq_owner_org_name exist via ddl-auto):
ALTER TABLE owner DROP INDEX UKjryjfrl809i37qthat5rwpnq5;   -- old global UNIQUE(name)
```
