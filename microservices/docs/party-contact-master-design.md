# Party / contact master — design

Starts the initiative decided in [[project_party_service_roadmap]]: a shared **party/contact master** owning only the
common identity of a person/organisation, referenced by every module via a stable `partyId` — the same pattern the
finance ledger (`partyType`+`partyId`+`partyName`) and Item→Product convergence (one master + id bridge) already use.
Cadence: **Document → Design (this) → sign-off → phased slices**. No code until sign-off — it stands up a new service.

## 1. Problem
The same real-world identity is re-entered per module: POS **Customer**, pharmacy **Patient**, education **Student**,
welfare **Donor**, buy-side **Vendor**, marketplace **account**. They share a *thin* surface (name, contact, email,
address, type, org) but differ *hugely* in domain data (AR/invoices/loyalty vs clinical/Rx vs enrollment/fees vs
pledges). There is no single contact record, so the same person is duplicated across modules, can't be de-duped, and
there's no cross-module "who is this" or unified contact/CRM view.

## 2. Decision (from roadmap) + non-goals
- **A shared `party-service` (contact/CRM master)** owns ONLY the common identity + issues a stable `partyId`.
- **NOT a customer god-service.** Each module keeps its domain entity + data, now *keyed by* `partyId` (bounded
  contexts intact — DDD). Party-service never holds AR, Rx, fees, pledges, loyalty, etc.
- **Non-goals:** no domain data in party-service; no big-bang data migration; no forced rewrite of module screens.

## 3. party-service shape
- **Port/DB:** new service (e.g. 8096, `myplusdb_party`), Flyway-owned, org-scoped (org_id + NULL-fallback), behind
  the gateway (`/api/party/**`), HeaderAuthFilter + `SecurityConfig` like the other services.
- **`party` entity:** `id (partyId)`, `organization_id`, `party_type` (CUSTOMER/VENDOR/STUDENT/DONOR/PATIENT/OTHER —
  superset of finance's enum), `name`, `contact`/`mobile`, `email`, `address`, `notes`, `created/updated`, `active`.
  Unique-ish natural key per org: `(organization_id, contact)` and/or `(organization_id, email)` for **de-dup/match**.
- **API:** CRUD (`/api/party/parties`), `GET /parties/lookup?contact=|email=` (match/find-or-create), `GET
  /parties/{id}` → `PartyRef`. commerce-contracts: `PartyClient` + `PartyRef` (mirrors `FinanceClient`/`ProductRef`).
- **Matching/de-dup:** `upsert(orgId, type, name, contact, email)` returns an existing party when contact/email match
  within the org, else creates one. This is what makes one person shared across modules.

## 4. The bridge — additive, low-risk first (the Item→Product lesson)
Item→Product succeeded by an **additive master-sync** before any rewire (catalog Product master + a synced projection),
*not* a big-bang. Mirror that:

- **Phase 1 (bridge, additive):** add nullable `party_id` to business `Customer` + `Vender`. On create/update, the
  module **upserts a Party** (find-or-create by contact/email) and stamps `party_id` — exactly how `ProductSyncService`
  projected Item↔Product. Screens untouched; the module still owns its Customer/Vender row + AR/AP. Now every POS
  customer/vendor has a shared `partyId`.
- **Phase 2 (finance uses the real partyId):** finance already records `partyType`+`partyId`; today that id is the
  local `customerId`/`venderId`. Switch the module to pass the **shared `party_id`** so a customer who is also a vendor
  or a pharmacy patient reconciles under one identity. (Finance is already party-agnostic — no finance change beyond
  the id it's handed.)
- **Phase 3 (other modules):** education `Student`, welfare `Donor`, pharmacy `Patient`, marketplace account each add
  `party_id` + the same upsert-on-write. A cross-module **contact/CRM view** (list a party's roles across modules)
  becomes possible.
- **Phase N (optional, YAGNI):** modules *read* identity from party-service instead of storing name/contact locally.
  Deferred — the additive bridge already delivers the shared-identity value; full read-convergence is later tech-debt
  (same call Item→Product made: master-sync first, teardown much later).

## 5. Why a new service (vs a shared lib / table)
Per [[feedback_microservice_standards]]: a genuinely reusable cross-cutting capability (contact/CRM master, used by
every vertical) → its OWN standalone service (contract + client, plug-and-play, DIP), not an in-service table or a
shared lib. It also becomes the home for future CRM (segments, comms preferences, merge/dedupe tooling).

## 6. Phased slices (each: Document→Design→Implement→Cypress)
1. **P0 — scaffold party-service** (entity/repo/service/controller, CRUD + upsert/lookup, Flyway V1, org-scoped,
   gateway route, config, start-all/compose) + commerce-contracts `PartyClient`/`PartyRef`. Additive; nothing wired.
   Cypress: create/lookup/dedupe by contact.
2. **P1 — business Customer/Vender bridge** (`party_id` + upsert-on-write, best-effort like the GL/audit hooks) +
   a read of the party on the customer/vendor screen (optional). Cypress: creating a customer projects a party;
   two customers with the same contact map to one party.
3. **P2 — finance uses the shared partyId** (business passes `party_id` as the ledger partyId).
4. **P3 — education/welfare/pharmacy/marketplace bridges** (one per slice) + the cross-module contact view.

## 7. Open decisions for sign-off
- **D1:** new **party-service** (recommended, per microservice standards) vs. a party table inside an existing service.
- **D2:** additive **master-sync bridge first** (recommended — the Item→Product lesson; low risk) vs. big-bang migration.
- **D3:** de-dup match key = `(org, contact)` primary, `(org, email)` secondary — confirm (some orgs reuse contacts?).
- **D4:** scope of slice 1 = **P0 scaffold only** (recommended) so we validate the service before touching modules.

## 9. Status update — P1 IMPLEMENTED (business Customer/Vender bridge)
- **business-service:** `Customer.partyId` + `Vender.partyId` (Flyway **V27** — V26 was taken by org_setting; additive); targeted `updatePartyId`
  on both repos (no full-entity save → no clobber); `PartyClient` bean (`lb://party-service/api/party/parties`);
  **`PartyBridgeService`** — best-effort `bridgeCustomer`/`bridgeVender`: skip when already bridged (`partyId != null`)
  so the repeat-sale hot path pays nothing; else `partyClient.upsert(PartyRef)` (find-or-create by contact→email) +
  stamp. Wired at register (`addCustomer`, `addVender`) AND the sale path (`CustomerService.saveUpdateCustomer`).
  `CustomerDTO.partyId` + `VenderDTO.partyId` exposed on reads.
- **Cypress `business/party-bridge.cy.js`:** a customer + a vendor sharing a contact resolve to the SAME partyId
  (cross-type de-dup = one identity). Build: commerce-contracts + business-service + monolith unchanged (existing
  proxies). **NEXT = P2** (finance uses the shared partyId) then **P3** (education/welfare/pharmacy/marketplace bridges
  + cross-module contact view).

## 8. Status: P0 IMPLEMENTED (scaffold); P1+ pending
Sign-off given (D1 new service, D2 additive bridge, D3 contact-primary/email-secondary, D4 P0 first).
- **party-service** (new, port **8096**, DB `myplusdb_party`, pkg `com.myplus.party`): `Party` entity + `PartyRepository`
  (scoped + de-dup finders) + `PartyDTO` + `PartyService` (list/search/get/create/update + **`upsert`** find-or-create
  by contact→email, `fillBlanks` enrich-don't-clobber) + `PartyController` (`/api/party/parties` CRUD + `/upsert`) +
  `SecurityConfig` (HeaderAuthFilter, stateless) + Flyway **V1** (`party`, unique `(org, contact)`) + pom/app/bootstrap/
  Dockerfile. Wired: parent pom, gateway route `/api/party/**` (no StripPrefix, CircuitBreaker+JwtAuth), start-all/
  stop-all (8096), docker-compose. No config-server config needed (local application.yml, like audit-service).
- **commerce-contracts:** `PartyRef` + `PartyClient` (`upsert`/`get`) — for P1 consumers.
- **Cypress `party/party-master.cy.js`:** gateway-direct (Bearer) — create a party; upsert same contact → same id;
  upsert same email diff contact → same id; get by id. (No monolith proxy yet — that's P1.)
- **Build/run:** `mvn -q -pl party-service -am clean install -DskipTests`; start via start-all (or the layered compose).
  P1 next = business `Customer`/`Vender` bridge (`party_id` + upsert-on-write).
