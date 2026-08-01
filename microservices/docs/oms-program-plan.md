# OMS / Platform Program — execution tracker

Live checklist for implementing [`platform-oms-master-reference.md`](platform-oms-master-reference.md). One slice at a
time: **Document → Design (Mermaid) → Implement → headed Cypress gate** (you run the gate; your pass closes the slice).
Branch **`feature/oms`**. Update the status column as slices land.

**Legend:** ⬜ not started · 🟡 in progress · 🟢 design approved, implementing · ✅ gate green.

## Main line — OMS (repair-in-place → extract → channels)

| # | Slice | Fixes / adds | Design doc | Gate | Status |
|---|---|---|---|---|---|
| **O1** | Storefront → books | OMS-1, OMS-5 | [oms-O1-storefront-to-books.md](slices/oms-O1-storefront-to-books.md) | `order-to-ledger.cy.js` | 🟡 **design ready — awaiting approval** |
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
| **B — B2B commercial** | account hierarchy+roles (party) → contract/tiered pricing + `/price/calculate` (catalog + `commerce-pricing`) → quotes→approval→order → credit limits/terms (finance AR) | 🟡 **foundation landed** — `Customer.customerType` ships in B2B Phase 0 (✅ green 2026-08-01, branch `feature/b2b-b2c`). **B4 credit limits = B2B Phase 1, B1 pricing = B2B Phase 2** — plan of record is [`b2b-b2c-rollout-plan.md`](b2b-b2c-rollout-plan.md); do NOT build them standalone here or the work is done twice |
| **C — Platform** | event broker + **CQRS analytics read-model** → hash-chained audit → metrics/SLOs → object storage → API versioning → shared `config-screen.js` | ⬜ |

## Housekeeping / prerequisites
- ⬜ **Verify G1** (expired-FEFO) — `mvn -pl inventory-service -am test -Dtest=ReservationServiceTest` (commit `492601d`, already in history).
- ⬜ Commit in-progress **education report-cards** work on `feature/education-review` (keeps `feature/oms` clean).

## Open decisions (from master §Appendix B — confirm as they arise)
1. First-cut scope: OMS correctness first (**chosen**) vs a parallel track first.
2. Invoice-trigger defaults: `ON_PAYMENT` (e-com) vs `ON_DELIVERY` (retail SO) — both configurable.
3. Legacy storefront orders: `LEGACY_UNPOSTED` + leave the books (**O1 default**) vs back-post at original dates (touches closed periods).
4. `order-service` extraction at O6 (after correctness, before 2nd channel) — confirmed by sequence.
