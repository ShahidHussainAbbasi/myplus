# MyPlus — Multi-Tenant SaaS Architecture (Standard)

This is the contract **every** microservice follows, because the **myplus monolith (Thymeleaf) is
the single front-end for all services**. Build domains as **vertical slices**
(Document → Design → Implement → Test), one domain 100% before the next.

## 1. The three-part service contract

1. **Flat endpoints returning `GenericResponse`** — the monolith JS expects
   `{status, message, object, collection, error}`, root-mapped (e.g. `/getUserSchool`), **not** REST
   `/api/...` with `ApiResponse`. The gateway routes `/api/<svc>/**` here via `StripPrefix=2`.
2. **Identity from headers** — the gateway validates the JWT and stamps `X-User-*`; `common-security`
   rebuilds an `AuthenticatedUser` principal. Services never re-validate the token.
3. **Tenant scoping by `organizationId`** — every domain row carries `organization_id`; reads/writes
   are filtered by the active org. `user_id` is kept only as **audit** ("who created it").

## 2. Identity vs. tenant — two different questions

| Field | Question it answers | Source | Used for |
|-------|--------------------|--------|----------|
| `userId` | **Who** performed the action | JWT `userId` → `X-User-Id` | audit / created-by |
| `organizationId` | **Which tenant's data** this is | JWT `activeOrgId` → `X-Org-Id` | data scoping |

The owner, their staff, and their students have **different `userId`s but the same
`organizationId`**, so they share one school's data. That is what `userId`-only scoping cannot do.

## 3. Why the client never sends the tenant id (security)

If the monolith sent `?orgId=1` and a service trusted it, any logged-in user could change it to `2`
and read another tenant's data (IDOR / broken tenant isolation — the #1 multi-tenant SaaS bug).
Instead the tenant is **derived server-side from the token**, and a user is only ever scoped to an
org they are a member of. Switching org (future multi-org users) is a server endpoint that verifies
membership and re-issues the token — never a free-form client parameter.

## 4. End-to-end flow

```
monolith dashboard (Thymeleaf + JS, JWT in server-side session)
        │  GET /getUserStudent     (flat endpoint, no orgId param)
        ▼
api-gateway  ── validates JWT ── stamps X-User-Id / X-User-Email / X-User-Roles /
        │                              X-User-Privileges / X-Org-Id (from activeOrgId claim)
        │                              + X-Internal-Secret
        ▼
resource-service (service-parent → common-security auto-config)
   HeaderAuthFilter → AuthenticatedUser { userId, email, authorities, organizationId }
   controller scopes query by organizationId; stamps user_id (audit) + organization_id on writes
        ▼
   GenericResponse {status, message, object|collection}
```

## 5. The tenant model (auth-service)

- `Organization` — the tenant. `ownerUserId`, optional `parentId` (branch), `type` (SCHOOL/COLLEGE).
- `Membership` — user ↔ org (many-to-many), per-org `role` (OWNER/ADMIN/TEACHER/STUDENT).
- On first login, `OrganizationService.getOrCreatePrimaryOrg(user)` auto-creates the user's org
  ("tenant #1") + an OWNER membership, so existing single-owner data has a home. The org id is put
  in the JWT as `activeOrgId`.

> **Single-owner == tenant #1.** The shippable single-owner app and the multi-tenant SaaS are the
> same codebase; single-owner is just one organization.

## 6. Migration: `user_id` → `organization_id`

Existing rows have `user_id` but no `organization_id`. Strategy (no cross-service DB coupling):

- New writes always stamp `organization_id`.
- Reads use a **scoped query with fallback**: `organization_id = :org OR (organization_id IS NULL AND
  user_id = :uid)`. The owner keeps seeing their pre-migration rows; the NULL set drains as rows are
  re-saved. Safe because org ids are distinct, so NULL-org rows only match their own creator.
- `auth-service` owns the `organizations`/`memberships` tables; resource services treat
  `organizationId` as an **opaque scoping key** received via header — they never join to it.

## 7. Per-slice definition of done

A domain slice is complete only when **all** are true:
1. Monolith dashboard section + JS call the flat endpoints (single shared UI look-and-feel).
2. Flat `GenericResponse` endpoints exist and match the JS contract.
3. Entity has `organization_id`; reads/writes scoped by `organizationId`; `user_id` kept for audit.
4. Cypress spec for the domain is green.
5. This doc set updated (slice design under `docs/slices/`).

See `docs/slices/` for per-domain designs.
