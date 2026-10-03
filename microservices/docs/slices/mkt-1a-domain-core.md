# MKT-1a: Multi-seller marketplace domain core (pure rules)

**Status:** IMPLEMENTED, unit-green. `mvn -pl marketplace-service test`: **205 run, 0 failures, 0 errors, 23
skipped**. The 23 skips are `FlywayMigrationTest` (6) and `OrderServiceTest` (17), both
`@Testcontainers(disabledWithoutDocker = true)`. This container has the Docker CLI but no daemon, so they did not
execute (D2a: reported, not counted as green). The 60 new MKT-1a cases all ran. Branch
`claude/e2e-analysis-testing-docs-999pqb` (based on `feature/expense-management` @ `7b87499e`).
Programme: [`../marketplace-multiseller-design.md`](../marketplace-multiseller-design.md) §5.6.

## 1. Document

Every later MKT slice needs the same rules: two products are the same or not; an offer is eligible or not; who
ranks first for a customer's sort; whether a status move is legal; what a seller is owed and whether it adds up;
which date T+1 is. If each endpoint re-derived them, the browse page and the checkout would disagree on why an
offer is unavailable. That is the "two copies of the lifecycle" defect O4 removed.

This slice puts those rules in one framework-free package, `com.myplus.marketplace.multiseller.domain`. They
depend on **no open ruling** (analysis §6): the merchant-of-record, money-collection and ledger-home rulings change
*where* the results are persisted, not what the rules compute. That is why this slice can start before the design
gate. Everything with a table, an endpoint or a screen waits for it.

**No caller yet, deliberately.** The standards' "a slice is not done until something CALLS it" applies to
capabilities a user reaches. This is a library slice (the EX-0b precedent). Its first callers are MKT-1b (identity
key) and MKT-1c (eligibility, terms). Until then it is unreachable by design, and it is not listed as a capability
anywhere.

## 1b. Standards

| Dimension | Rule |
|---|---|
| Business / domain | Source §6 (composite key; GTIN only when the check digit is valid), §10 (availability formula, acceptance terms), §11 (substitution needs approval), §13 (cost bearer by cause, via snapshot), §15 (T+1 business days; reconciliation identity), §17/§20 (Phase 1 scope), §18 (ranking chain; customer sort primary), §19 (independent machines) |
| SaaS multi-tenancy | No I/O, no tenant. Party ids arrive in a snapshot the caller built from server-side data; nothing here accepts an org id from a browser |
| Live-modules rule | New package, no existing class changed (one test-harness fix below), no migration. Zero runtime effect until a later slice calls it |
| Microservice boundaries | Lives in marketplace-service, the only owner of these aggregates. Not a shared library: no second service needs these rules today (decision rule: a library when the rules are shared) |
| Design patterns | **State machine whitelist** (`StateMachine<E>`, the `FulfilmentStatus.ALLOWED` shape, generalised) · **Specification** (`OfferEligibility`: one predicate returning the refusal sentence) · **Strategy** (`OfferRanker.primary`, one comparator per sort; `CommissionPolicy.Basis`) · **Value objects/records** (`Terms`, `Breakdown`, `OfferCandidate`, `PartySnapshot`) |
| SOLID / DRY | Reuses `commerce-domain.Money` for scale and rounding. Refusal sentences live in one place for browse and checkout. Each machine publishes `allowedFrom` so no UI keeps its own copy |
| Testing | 60 unit cases, each display name carrying the source requirement id(s) (`[MKT-Rx.y]`), so the test plan's matrix is computed from the source tree. One mutation check recorded in §5 |

## 2. Design

| Class | Source | Rule |
|---|---|---|
| `StockSourceType` | §1, §4 | four sources, each with the phase that enables it |
| `MarketplaceStatus` | §5, §6, §10, §11, §16, §19 | the status families, each its own type |
| `StateMachine<E>`, `MarketplaceStateMachines` | §19 | ORDER, SELLER_ORDER, PAYMENT, SETTLEMENT, RESERVATION whitelists. Published sets are read-only. Every state is reachable (tested) |
| `ProductIdentityKey` | §6 | general and medicine keys; conservative normalisation; separator can't be injected; GS1 check digit |
| `Availability` | §10 | `on_hand − reserved − allocated − unavailable`, clamped at 0 |
| `AcceptanceTerms` | §10 | per-source accept/hold defaults; the hold can't be shorter than the window |
| `BusinessDayCalendar` | §15 | T+N over a configurable weekend and holiday set |
| `CommissionPolicy`, `SettlementCalculator` | §7, §15 | ITEMS / ITEMS_PLUS_DELIVERY / FIXED; payable derived; negative refused; `reconciles()` |
| `OfferCandidate`, `EligibilityContext`, `OfferEligibility` | §7.6, §9.2, §18.5 | guardrails with the customer-facing refusal sentence |
| `OfferSort`, `OfferRanker` | §7, §18 | filter first, then customer sort, then the operator chain, then the id |
| `PhaseGuard` | §17, §20 | one seller per checkout before Phase 2; sources by phase; regulated blocked |
| `ReturnCostPolicy` | §13 | the 10-row table; bearer resolved through the order-time snapshot |
| `SubstitutionPolicy` | §11 | protected attributes, price up, or later promise → ask the customer |

## 3. Architecture & UML

Class diagram: programme design §5.6. No sequence (no I/O) and no ER (no tables) in this slice.

## 4. Implement

- [x] domain package + `package-info`
- [x] 19 classes listed in §2
- [x] 10 test classes, 60 cases
- [x] full `mvn -pl marketplace-service test` read for skips
- [x] **Found on the way, fixed (test harness only):** `CheckoutServiceTest` had 11 errors on the base branch.
  `CheckoutService.taxPolicyCache` is built in `@PostConstruct` (PERF-D5, `53c35de9`), which `@InjectMocks` never
  calls, so every quote NPE'd before reaching its assertion. Reproduced on a clean `feature/expense-management`
  worktree (`Tests run: 14, Errors: 11`) before touching it. Fix: the test's `@BeforeEach` calls
  `service.initTaxPolicyCache()`, as Spring does. No production change.

## 5. Test

```
mvn -B -pl marketplace-service test -Dtest='com.myplus.marketplace.multiseller.**'
  AcceptanceTermsTest 2 · AvailabilityTest 4 · BusinessDayCalendarTest 4 · MarketplaceStateMachinesTest 8
  OfferEligibilityTest 9 · OfferRankingTest 8 · PhaseGuardTest 6 · ProductIdentityKeyTest 8
  ReturnAndSubstitutionPolicyTest 5 · SettlementCalculatorTest 6          = 60 run, 0 fail, 0 skipped
```

**Mutation check (a test that has never failed proves nothing).** `commerce-domain.Money.multiply` scales *both*
operands to 2 dp, so using it for a commission turns 12.5% into 13%. The first draft would have done exactly that.
Swapping `CommissionPolicy.applyRate` back to `Money.multiply` turns
`SettlementCalculatorTest.fractionalRateNotRoundedFirst` red (`expected: 125.00`). Restored afterwards.

**Cypress:** none for this slice (no screen). The programme gate specs are written ahead under
`cypress/e2e/marketplace/`, phase-gated (see the test plan).
