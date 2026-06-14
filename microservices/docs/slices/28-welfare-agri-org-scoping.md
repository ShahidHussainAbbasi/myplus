# Slice 28 — Welfare + Agriculture org-scoping (multi-tenant)

Status: **DONE + VERIFIED** ✅ (2026-06-14). Build ✓; service-level smoke ✓ — welfare getUserDonation(s),
agri getUserAgricultureIncome/getUserLand/getAllLand all 200 (org-scoped findScoped executes, no 500;
getAllLand leak closed). No Cypress (welfare/agri have none). Tech-debt #18 (🟠). Applies the slice-21 recipe to the last two
`userId`-only services. **No Cypress specs exist for welfare/agri** (deferred) → verification is
compile + (optional) manual; that test gap is itself tech-debt.

## Document — what & why
welfare-service and agriculture-service were still `userId`-only with `getAll`/`findAll()`-style reads
that leak across tenants. Bring them to the [`ARCHITECTURE-MULTITENANCY`](../ARCHITECTURE-MULTITENANCY.md)
standard like business (slice 21) and education.

## Design (same recipe)
Each entity gains `organization_id` (nullable; `user_id` kept as audit). Each repo gets
`findScoped(orgId, userId)` with NULL-fallback (`org = :org OR (org IS NULL AND user = :user)`). Reads go
through `findScoped`; writes stamp `organizationId`; deletes re-check tenant ownership (anti-IDOR);
dup-checks scoped within the tenant. No global unique constraints to drop (Land's unique is per-user
`(land_name, user_id)` — left as-is). No destructive migration (`ddl-auto: update` adds the column).

### Welfare (DONE)
- `Donation`, `Donator` + `organization_id`; `DonationRepo`/`DonatorRepo.findScoped`; service ifaces+impls.
- `DonationController`: `getUserDonations` (was `findAll()` leak) / `getUserDonation` / `getUserDonator` /
  `getAllDonators` → `findScoped`; `addDonator` scoped dup + stamp org; `addDonation` stamp org;
  `deleteDonator`/`deleteDonation` org-check.

### Agriculture (PENDING)
- `AgricultureIncome`, `AgricultureExpense`, `Land` + `organization_id`; repos `findScoped`; ifaces+impls.
- `AgricultureIncomeController`/`AgricultureExpenseController`/`LandController`: reads → `findScoped`;
  `add*` stamp org + scoped dup; `delete*` org-check; `loadLastCropAttached` scoped + landId filter.

## Implement (checklist)
- [x] Welfare: entities, repos, services, controller
- [x] Agriculture: Income, Expense, Land — entities +org_id; repos findScoped; ifaces+impls; controllers
  reads→findScoped (incl. getAllLand leak), writes stamp org, deletes org-checked (income/expense via
  findScoped membership set, land via findById), loadLastCropAttached scoped + landId filter. Kept the
  user-scoped Example dup-checks (existence probes include userId — not a leak).
- [ ] build welfare-service + agriculture-service (no Cypress — compile gate) — **awaiting build**

## Test
- Compile both services (Hibernate adds `organization_id`).
- Manual/JWT: a second org cannot see the first org's donations/income/expense/land.
- (Follow-up tech-debt) add welfare/agri Cypress specs — currently zero.
