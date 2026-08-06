# OMS / Platform Program — execution tracker

**Status:** LIVE TRACKER - the status column in each table is the source of truth for OMS/platform work. Update the row as a slice lands, not this line.

Live checklist for implementing [`platform-oms-master-reference.md`](platform-oms-master-reference.md). One slice at a
time: **Document → Design (Mermaid) → Implement → headed Cypress gate** (you run the gate; your pass closes the slice).
Branch **`feature/oms`**. Update the status column as slices land.

**Legend:** ⬜ not started · 🟡 in progress · 🟢 design approved, implementing · ✅ gate green.

## Main line — OMS (repair-in-place → extract → channels)

| # | Slice | Fixes / adds | Design doc | Gate | Status |
|---|---|---|---|---|---|
| **O1** | Storefront → books | OMS-1, OMS-5 | [oms-O1-storefront-to-books.md](slices/oms-O1-storefront-to-books.md) | `order-to-ledger.cy.js` | ✅ **GREEN 2026-08-06 — 10/10.** Storefront orders now go through business-service's ONE sale path: invoice + GL + tax + AR, server-priced (OMS-5), idempotent per cart. Cancel VOIDS the invoice (stock back **and** books reversed) via `SaleVoidService`, extracted so `/voidSell` and `/internal/sales/reverse` share one reversal. Duplicate marketplace saga + `OrderSagaRecoveryRelay` deleted. Built on `feature/b2b-b2c`. Reconciliation read `GET /orders/reconciliation` (owner/admin) lists the pre-O1 backlog. **Open:** CARD tender not yet passed into the sale, so a paid card order reads as AR until the PSP slice. |
| **O2** | Lifecycle + authority + safety | OMS-2/3/4/8 | _tbd_ | `order-lifecycle.cy.js` | ⬜ |
| **O3** | `order.*` per-org config | 🔒 config | _tbd_ | `order-config.cy.js` | ⬜ |
| **O4** | Back-office UI/UX | OMS-7 | _tbd_ | `order-back-office.cy.js` | ⬜ |
| **O5** | Fulfilment engine | OMS-6 + partial/split/backorder/carrier | _tbd_ | `order-fulfilment.cy.js` | ⬜ |
| **O6** | Extract `order-service` (:8097) | service split + dual-read | _tbd_ | all prior specs green | ⬜ |
| **O7/O8** | Channels + vertical adapters + CQRS read-model | POS SO, procurement PO/GRN, pharma dispense, appointment, education/welfare | _tbd_ | per-vertical | ⬜ |

## Parallel tracks (interleave independently)

| Track | Scope | Status |
|---|---|---|
| **A — Config/Authz rollout** | `common-settings` → catalog/inventory/finance/pharma/marketplace/appointment; finish `@PreAuthorize` tail | ⬜ |
| **B — B2B commercial** | account hierarchy+roles (party) → contract/tiered pricing + `/price/calculate` (catalog + `commerce-pricing`) → quotes→approval→order → credit limits/terms (finance AR) | 🟡 **B1 + B4 DONE; documents/reports DONE** — `feature/b2b-b2c` Phases 0–3 all Cypress-green (2026-08-01→04). **B1** = contract/tier pricing via the new `commerce-pricing` library (rules never stack; one quote per sale). **B4** = credit limit + payment terms, customer AND supplier, warn = take confirmation. Also landed: CRN-/DBN- return documents, batch traceability IN and OUT, CSV statements, report filters + grouping. **Phase 3 CLOSED 2026-08-04** including **3f** — statements now show credit/debit notes and invoices are no longer retro-edited (an invoice issued at 500 used to read 300 after a return, contradicting the customer's own copy). **Phase 3g CLOSED 2026-08-05** (printable trade invoice + owner-designable documents; renderer and designer both gated). **Phase 4a — the ACCOUNT HIERARCHY — CLOSED 2026-08-05, 8/8 green**: company→branch→contact on `Party` (so education corporate sponsors and welfare corporate donors inherit the structure), shared-pool credit **stamped** on `Customer.creditAccountCustomerId` so the sell path keeps a single local read. `common-credit` needed no change — `CreditLimitPolicy` was already correct pure arithmetic. **Phase 4b CLOSED 2026-08-06, 6/6 green** — `SalesQuote` (own `QTE-` series, `@Version`, derived expiry, internal
approval gate distinct from customer acceptance) converting through the SAME revenue path, customer PO carried to
the printed invoice, and **D-4 settled + implemented**: trade discount posts to 4200 Sales Discount
(contra-revenue), where it had been captured since 3g but never posted. **TRACK B IS COMPLETE.** ⚠️ Note the gate caveat: every slice above passed a *single-spec* gate; the full business suite was not run until 2026-08-04 and needed [slice 106](slices/106-cypress-suite-health.md) before it could be trusted. |
| **C — Platform** | event broker + **CQRS analytics read-model** → hash-chained audit → metrics/SLOs → object storage → API versioning → shared `config-screen.js` | ⬜ |

## Housekeeping / prerequisites
- ⬜ **Verify G1** (expired-FEFO) — `mvn -pl inventory-service -am test -Dtest=ReservationServiceTest` (commit `492601d`, already in history).
- ✅ Commit in-progress **education report-cards** work on `feature/education-review` (keeps `feature/oms` clean) — done: 1.5 report cards is commit `67365448`, 1.6 promotion `cd3f35dd`. That branch has since finished all of Phase 1, closed the education review, and shipped Phase 2.1 (timetable), 2.2 (substitution) and 2.3 (staff attendance &amp; leave), plus 2.4 (homework) and 2.5 (behaviour log) — **Phase 2 is COMPLETE**, all green by 2026-08-03. Next there is Phase 3 (parent/student portals, gated on D-2 and D-4), with a notification slice the strongest candidate first.

- ✅ **Deploy topology hardened 2026-08-05** — `docker-compose.yml` gained **compose profiles** (default = the 14-service POS subset · `--profile pharmacy` = +pharma · `--profile full` = all 22), replacing the hand-typed runbook service list that had already drifted and killed a deploy ("no such service: party-service"). Every app service gained a **readiness** healthcheck (`/actuator/health/readiness`, not `/actuator/health` — the latter aggregates `db`, which flaps DOWN under load and would take healthy containers with it), and the monolith now waits on `api-gateway: service_healthy`. `start_period` 240s after a measured thundering-herd failure at 90s. Memory ceilings measured: POS 10.75 GB · pharmacy 11.5 GB · full 16.75 GB.

## Open decisions (from master §Appendix B — confirm as they arise)
1. First-cut scope: OMS correctness first (**chosen**) vs a parallel track first.
2. Invoice-trigger defaults: `ON_PAYMENT` (e-com) vs `ON_DELIVERY` (retail SO) — both configurable.
3. Legacy storefront orders: `LEGACY_UNPOSTED` + leave the books (**O1 default**) vs back-post at original dates (touches closed periods).
4. `order-service` extraction at O6 (after correctness, before 2nd channel) — confirmed by sequence.
