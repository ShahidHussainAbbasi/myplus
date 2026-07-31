# Deployment runbooks

One runbook per module. Everything common — prerequisites, secrets, VPS build-out, TLS, firewall,
operations, troubleshooting — lives once in **[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)**, and each module
runbook covers only what it adds.

That split is deliberate: copying the nginx/TLS/firewall sections into seven files means the next change
has to be made seven times, and six will be missed.

## Pick your runbook

| Deploying | Runbook | Services on top of the platform | RAM |
|---|---|---|---|
| **POS / Retail** | [`../../DEPLOY-POS-RETAIL.md`](../../DEPLOY-POS-RETAIL.md) | catalog, inventory, business, finance, audit, party | ~11 GB |
| **Pharmacy** | [`DEPLOY-PHARMACY.md`](DEPLOY-PHARMACY.md) | POS stack **+ pharma** | ~11.5 GB |
| **E-commerce** | [`DEPLOY-MARKETPLACE.md`](DEPLOY-MARKETPLACE.md) | catalog, inventory, business, marketplace, finance, party | ~10.7 GB |
| **Education** | [`DEPLOY-EDUCATION.md`](DEPLOY-EDUCATION.md) | education, finance, party | ~8.5 GB |
| **Welfare** | [`DEPLOY-WELFARE.md`](DEPLOY-WELFARE.md) | welfare, party | ~7.8 GB |
| **Agriculture** | [`DEPLOY-AGRICULTURE.md`](DEPLOY-AGRICULTURE.md) | agriculture | ~7.8 GB |
| **Appointments** | [`DEPLOY-APPOINTMENT.md`](DEPLOY-APPOINTMENT.md) | appointment | ~7.8 GB |
| **Everything** | [`DEPLOY-FULL-STACK.md`](DEPLOY-FULL-STACK.md) | all 19 | ~16.5 GB |

The platform baseline (mysql, redis, eureka, config, gateway, auth, notification, monolith) is ~7 GB and
is required by every module — see COMMON §1.

## Read these regardless of module

- **COMMON §3** — secrets, and why `APP_SEED_DEMO` must never be set on a public host
- **COMMON §6** — the pre-traffic checklist, including the query that proves no seeded demo account survives
- **COMMON §8** — the stale-jar trap. `mvn compile` refreshes `target/classes` but **not** the jar, so the
  container keeps running old code while the source looks correct. `package`, always.
- **COMMON §9** — `party-service` is documented as part of several stacks but is **missing from
  `docker-compose.yml`**. Contained by design (all bridges are best-effort), but Contact 360 stays empty
  until it is added.

## Public surfaces

Two modules serve unauthenticated traffic, and each has its own hardening section:

- **Marketplace** — `/store`, `/storefront/**` ([§7](DEPLOY-MARKETPLACE.md))
- **Appointments** — `/appointment`, `/appointmentReq`, `/registerHospital` ([§5](DEPLOY-APPOINTMENT.md))

Both need nginx rate-limiting; neither gets it from the gateway's demo-quota counter, which is not a rate
limiter.

## Modules with no automated coverage

**Welfare** and **agriculture** have no Cypress specs. Smoke-test them by hand after every deployment —
nothing will catch a regression for you.

## Related

- `docs/DEPLOYMENT-RUNBOOK.md` — the older single-host runbook
- `docs/cicd-aws-review.md` — ECS/Fargate + GitHub Actions
- `microservices/docs/ARCHITECTURE-MULTITENANCY.md` — the org-scoping standard
- `microservices/docs/SAAS-BUILD-STANDARDS.md` — the governing build standards
