# MKT-1b: Canonical marketplace products + match review

**Live verification 2026-10-03:** headed gate **9/9 on a live stack** (48/48 across MKT-0a…1e in one run) and every manual case walked step by step and recorded — [live verification](../marketplace/live-verification-2026-10-03.md). The status below is the record from before that run.

**Status:** IMPLEMENTED, unit-green. **Headed Cypress gate written (run 2026-10-03, see above)** (no running stack in this
container). `marketplace-service` **234 run / 0 failed / 23 skipped**. The skips are Testcontainers, so **V25 has
not been executed against MySQL** (D2a). The monolith compiles. The seller fragment renders in en/ur/ar with every
key resolved. Programme: [`../marketplace-multiseller-design.md`](../marketplace-multiseller-design.md). Depends on
MKT-0a (every seller call goes through `assertActiveSeller`).

## 1. Document

A customer must see **one** "Samsung Galaxy A32 128GB Black" however many shops sell it (source §5). Today each
shop's phone is an unrelated row in its own catalogue. This slice creates the marketplace's own product record and
the review that links a seller's product to it.

**The rule that shapes everything (source §6): never merge on a name.** The composite key (MKT-1a) only
*proposes*. A person at MaxTheService decides. A second seller with an identical key gets a **suggestion**, not a
merge. A different storage or pack size produces a different key and no suggestion at all.

## 1b. Standards

| Dimension | Rule |
|---|---|
| Business / domain | Canonical product + seller source (the Amazon ASIN / Mirakl product-mapping model). GTIN only when its GS1 check digit is valid. A regulated product can never be attached to an ordinary canonical row (no laundering) |
| SaaS multi-tenancy | `mkt_product_source` is tenant-scoped by the JWT org. `mkt_product` deliberately has **no** `organization_id`: it belongs to no tenant and only the operator writes it. **Ownership of the seller's product is proven by catalog-service**: `getProductsFresh` runs `findAllByIdScoped(ids, org, user)` under the seller's forwarded identity, so another tenant's product is *absent* and reads as "not in your catalogue". Absent and foreign are indistinguishable (no existence probe) |
| Live-modules rule | Two new tables; nothing existing changes. Unreachable unless MKT-0a's three conditions hold |
| Microservice boundaries | marketplace-service owns canonical products. catalog-service stays the owner of each seller's product; this slice reads it (live, `fresh=true`, because the rx flag is a safety decision) and never writes it. No contract change: `ProductRef` already carries `name`, `rxRequired`, `controlledSubstance` |
| Design patterns | **Canonical model + mapping** · **State machine** `MarketplaceStateMachines.MATCH` (the operator screen offers the same edges) · **Unique constraint as the concurrency guard** (`uk_mkt_product_identity`; a lost race reads the winner's row) · **Optimistic lock** on decisions |
| SOLID / DRY | Key from `ProductIdentityKey`, regulated refusal from `PhaseGuard` (MKT-1a): the same code checkout will use. One error-relay path in the monolith controller (`relayGet`, `relayError`, `refusal`) |
| Testing | 13 unit cases (`MarketplaceCatalogServiceTest`) incl. mutation check (§5). Gate `mkt-1b-product-match.cy.js`, 9 cases, first drives the seller UI, third drives the operator UI |

## 2. Design

**Schema (V25, idempotent, attributes as columns: no JSON/TEXT, no ENUM):** `mkt_product` (UNIQUE `identity_key`)
and `mkt_product_source` (UNIQUE `(organization_id, source_product_id)`, indexes for the operator queue, the seller
list, key lookups and "who sources this product").

**What is trusted from where:**

| Field | Source |
|---|---|
| seller org | JWT |
| product exists + belongs to seller, its name, `rxRequired`, `controlledSubstance` | catalog-service, live |
| brand, model, variant, colour, size, pack size, condition, warranty, GTIN | seller's declaration, reviewed by the operator |
| identity key | computed by the server (`preferGtin(gtin, general(...))`); a client-sent key is ignored |

**Lifecycle (`MATCH`):** `PENDING_REVIEW → MATCHED | NEEDS_CORRECTION | REJECTED`;
`MATCHED → NEEDS_CORRECTION | REJECTED` (the operator corrects after the fact, MKT-R6.5);
`NEEDS_CORRECTION | REJECTED → PENDING_REVIEW` (the seller re-proposes, same row, one history). A MATCHED proposal
cannot be re-proposed by the seller. NEEDS_CORRECTION and REJECTED require a note the seller sees as written.

**MATCHED picks the canonical product** as: the operator's explicit choice, else the suggestion, else a new product
built from the proposal (name from its attributes, `APPROVED`, regulated status copied).

**Contracts:**

| Monolith route | → marketplace-service | Who |
|---|---|---|
| `POST /mkt/proposeProduct` | `POST /mkt/products/propose` | active seller |
| `GET /mkt/myProposals` | `GET /mkt/products/proposals` | seller (own org) |
| `GET /platform/mkt/matchQueue?status=` | `GET /mkt/operator/matches` | `ROLE_ADMIN` |
| `POST /platform/mkt/decideMatch {id, decision, mktProductId, note, version}` | `POST /mkt/operator/matches/{id}/decision` | `ROLE_ADMIN` |
| `GET /platform/mkt/products?q=` | `GET /mkt/operator/products` | `ROLE_ADMIN` |

**UI:** the seller's Marketplace screen gains "Products on the marketplace" (`#mktProductsBox`, shown only when
`canSell`). It has a propose form (the product picker reads `/getUserProduct`) and the proposals table with the
operator's note. The operator console gains "Product matching" (`#platMktMatches`, `#mktMatchQueue`). i18n: 39 keys
× 6 bundles (2716 each, none missing, computed by script).

## 3. Architecture & UML

```mermaid
sequenceDiagram
  actor S as Seller (active)
  participant M as marketplace-service
  participant C as catalog-service
  actor O as Operator
  S->>M: POST /mkt/products/propose {sourceProductId, attributes}
  M->>M: assertActiveSeller()
  M->>C: getProductsFresh([id], fresh=true) as the seller
  alt absent (or another tenant's)
    M-->>S: 404 "That product is not in your catalogue."
  else rxRequired / controlledSubstance
    M-->>S: 400 "Prescription and restricted products cannot be sold on the marketplace yet."
  end
  M->>M: key = preferGtin(gtin, general(...)); suggestion = product with that key (no merge)
  M-->>S: PENDING_REVIEW
  O->>M: decision MATCHED (suggested | chosen | new)
  M-->>O: mktProductId (UNIQUE key; a lost race reads the winner)
```

## 4. Implement

- [x] V25, `MarketplaceProduct`, `MarketplaceProductSource`, repositories, `MATCH` state machine
- [x] `MarketplaceCatalogService` + `MarketplaceCatalogController`
- [x] Monolith proxies (5) + seller block + operator panel + i18n ×6
- [x] 13 unit cases + mutation check; full suite 234/0/0 (23 skipped)
- [ ] Headed gate: `npx cypress run --headed --env mkt=1b --spec cypress/e2e/marketplace/mkt-1b-product-match.cy.js`
- [ ] `FlywayMigrationTest` with Docker (`Skipped: 0`)
- [ ] Manual walk (MKT-1b section of the manual-testing page)

## 5. Test

**Mutation check.** Making `propose` silently attach the suggested product (an auto-merge) turns
`suggestionNotMerge` red. Restored afterwards.

**Defect caught by re-reading before the first test run:** `ownProduct` returned `null` when catalog answered with
no body, which would have surfaced later as an NPE (a 500) instead of "not in your catalogue". It now treats an
absent body as an empty list.

**Known limit, stated:** the seller's product picker loads the whole active catalogue into a `<select>`.
`/getUserProduct` reads every page (PS-2), so nothing is silently dropped, but a shop with several thousand products
gets a long list. The shared `product-picker.js` search is the follow-up if a pilot seller has a large catalogue.
