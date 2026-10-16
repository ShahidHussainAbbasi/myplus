# HC-1c / HC-1d — the clinic ↔ pharmacy LINK, and prescriptions across it · parent `hc-1-healthcare-split.md`

Status: **DECIDED by the user 2026-10-15** — D2 (a) the linked pharmacy's medicine list (names + strengths, never stock or price) + free text; D3 no diagnosis; D1 one active pharmacy per clinic; D4–D6 as recommended. Nothing built.

## How a prescription travels TODAY (one combined tenant — traced 2026-10-15)
1. The doctor picks medicines from the **pharmacy's own product catalogue** (`clinic.js` `rx.lines` carry `productId`;
   the list comes from `clinic/consult/encounters/{id}/rx/pharmacy`).
2. Lines are saved in clinical-service (`encounter_rx_item`, product ids).
3. **Submit** (`ConsultService.submitRx`) calls pharma-service `create` with the **doctor's own token** → the
   prescription is written in the **same organisation** (`PrescriptionService.create`: org from the caller,
   `externalRef = enc-<id>` makes it idempotent, `storeId` = the doctor's pharmacy branch, H4).
4. The pharmacy dispenses it (`rx.dispense`); the clinic reads the status back by `rxId`, same org.

**Every step assumes one tenant.** Separated (HC-1a/b), each breaks: the clinic has no catalogue to pick from, the
doctor's token belongs to the clinic org so the prescription would land in the CLINIC, and nothing tells pharma-service
which other tenant may write to it. Nothing crosses tenants anywhere in the platform today — this is the first.

## Decisions for the user
**D1 · The link.** A pharmacy turns on *"Clinics may send us prescriptions"* (off by default) and can then be found by
name / city. A clinic finds it and sends a **link request**; the **pharmacy accepts**; either side can **end** it. Both
sides' audit trails record each step. Recommend: **one active pharmacy per clinic** for now (Tahseen has one); more later.

**D2 · How the doctor names a medicine** (the clinic has no catalogue of its own):
- (a) **From the linked pharmacy's medicine LIST — names and strengths only, never stock or price** (privacy rule kept),
  plus a free-text line for anything not on it. *Recommended:* the pharmacy gets lines it can dispense straight away.
- (b) Free text only; the pharmacist matches each line to a product when dispensing.

**D3 · What crosses to the pharmacy** (data minimisation): patient **name, phone, MRN**, the **medicines** (dose,
frequency, duration, quantity), the **prescriber**, the date. *Not* the diagnosis / complaint or any clinical note —
recommend leaving them out unless you want the pharmacist to see the diagnosis.

**D4 · How it is sent.** clinical-service → pharma-service **service-to-service**, carrying the clinic's identity; the
pharmacy side checks there is an ACTIVE link from that clinic before writing, and writes the prescription in the
**pharmacy's** organisation, marked *From clinic: Tahseen Healthcare Centre*. Same idempotency (`clinic-<org>-enc-<id>`).
The pharmacy sees only prescriptions sent to it; it never reads the clinic's patients.

**D5 · What comes back.** The clinic sees per prescription: **Sent · Dispensed · Partly dispensed · Not available**
(and which lines) — not prices, not stock.

**D6 · Order.** **HC-1c first** (the link: setting, search, request, accept, end, audit) — small and safe, and it is
where privacy is decided. Then **HC-1d** (prescriptions over it). Each: Test Book cases first → gate red → build →
you deploy.

## Where it lives (proposal, to be traced in the HC-1c design)
`org_link` in **auth-service** (it already owns organisations and the business types; one table, two org ids, status
REQUESTED / ACTIVE / ENDED, who and when for each step). The pharmacy's opt-in is an `org_setting`.

## HC-1c design — the link (traced 2026-10-15; awaiting "go" to build)
**Data (auth-service, next free V):** `org_link` (id, clinic_org_id, pharmacy_org_id, status REQUESTED/ACTIVE/ENDED,
requested_by/at, accepted_by/at, ended_by/at, end_reason). One REQUESTED-or-ACTIVE link per clinic (D1), enforced in the
service under a row lock on the clinic's organisation. Pharmacy opt-in: `org_setting` `org.link.acceptClinics` (auth) —
not a Configuration-screen entry (a catalog entry cannot be limited to pharmacies; every shop would see it).

**Endpoints (auth, owner/admin of the calling org only; the org is ALWAYS the token's, never a parameter):**
| | who | does |
|---|---|---|
| `GET  /api/auth/links/pharmacies?q=` | CLINIC | opted-in, ACTIVE PHARMA orgs by name — id + name only |
| `POST /api/auth/links` `{pharmacyOrgId}` | CLINIC | request (refused: not opted-in / not PHARMA / already has one) |
| `GET  /api/auth/links` | both | my links (the other side's NAME only) |
| `POST /api/auth/links/{id}/accept` | PHARMA | only a REQUESTED link addressed to my org — any other id answers "not found" |
| `POST /api/auth/links/{id}/end` `{reason}` | both | either side, reason required |
| `GET/POST /api/auth/links/settings` | PHARMA | read / set `acceptClinics` |
Every step → `ControlPlaneAuditService.tenantAction` for BOTH organisations.

**Screens (monolith proxies):** pharmacy — Pharmacy menu → **Linked clinics** (`#LinkedClinicsDiv`): the switch and the
requests / links table. Clinic — **Linked pharmacy** (`#PharmacyLinkDiv`, owner/admin menu): search, request, status,
end. Clinic home checklist gains **Link your pharmacy** (ticks on ACTIVE).

**Gates (written first):** `cypress/e2e/docs/hc-1c-link-guide-screens.cy.js` (L1–L6, recorded for Test Book §34) and
`cypress/e2e/healthcare/hc-1c-link-api.cy.js` (refusals: a shop cannot search; an unopted pharmacy is not found;
another pharmacy cannot accept; a second request is refused; a clinic member who is not owner/admin is refused).

## HC-1c BUILT (2026-10-15, user "ok") — not compiled / deployed
| Layer | Change |
|---|---|
| auth **V37** | `org_link` (InnoDB; status REQUESTED/ACTIVE/ENDED; ended_by_org_id; end_reason) |
| auth | `OrgLink` + `OrgLinkRepository`; `OrgSettingRepository.findBySettingKeyAndSettingValue`; `OrganizationRepository.lockById` (PESSIMISTIC_WRITE — one link per clinic); `OrgLinkService` (rules; refusals = `LinkRefusedException(code)`); `OrgLinkController` `/api/auth/links` (owner/admin; refusal = 200 + success:false + `reason` code); `ControlPlaneAuditService.linkAction` (one row per business); `OrgLinkServiceTest` (8 cases) |
| monolith | `OrgLinkController` `/links/**` pass-through (form → JSON; HTTP refusal → success:false); `fragments/org-links.html` (pharmacy + clinic panels); `js/common/org-links.js`; Pharmacy menu **Linked clinics**; clinic menu **Linked pharmacy**; clinic home step **Link your pharmacy**; keys `ui.lc.*`, `ui.pl.*`, `ui.js.link.*` (each refusal code has its own key) |
| specs | `docs/hc-1c-link-guide-screens.cy.js` (L1–L6), `healthcare/hc-1c-link-api.cy.js` (A1–A6) — not yet run |
Known: the other business's audit row carries actor type MEMBER (no "linked business" actor type exists) — its details name who acted.

## HC-1d design — prescriptions across the link (traced 2026-10-16; awaiting "go")
### Review — what breaks for a SEPARATED clinic today (counted)
| # | Step | Today | Evidence |
|---|---|---|---|
| T1 | The doctor's list | the CLINIC's own catalogue (`/catalogProductPicker`, scoped to the caller) — empty for a clinic | `clinic.js loadMedicines` → `product-picker.js:36` |
| T2 | A line not in the list | refused on screen ("not in the pharmacy's list") AND by pharma (`productId == null` → refused) AND by the schema (`encounter_rx_item.product_id NOT NULL`) | `clinic.js:944`, `PrescriptionService:54`, clinical V5 |
| T3 | Submit | pharma `POST /prescriptions` with the DOCTOR's token → written in the **clinic's** org; `diagnosis` = the chief complaint | `ConsultService.submitRx` 260–275 |
| T4 | Product check | `PrescribedProductCheck` reads the catalogue of the CALLER (the clinic) → every pharmacy product is "not in this pharmacy's list" | `PrescribedProductCheck` |
| T5 | Branch routing (H4) | `storeId` = the doctor's pharmacy branch — a CLINIC store id, meaningless in the pharmacy | `routeOf` |
| T6 | Person | `partyId` = the clinic's party id — another tenant's id if sent across | `submitRx` 261 |
| T7 | Status back | `pharma.get(rxId)` scoped to the caller → not found once the prescription lives in the pharmacy | `pharmacyCopy` 326 |
| T8 | Idempotency | `externalRef = enc-<id>` UNIQUE per org — two clinics' `enc-5` would collide in one pharmacy | pharma V9 |
Unaffected: the combined tenant (org 15, clinic + pharmacy in one org) — every step above is correct there and stays.

### Design
**R1 · Who decides the route: auth, asked by both sides.** New internal `GET /api/auth/internal/links/route` (X-Internal-Secret
+ forwarded org; not routed by the gateway) → `{ route: SAME_ORG | LINKED | NONE, pharmacyOrgId, pharmacyName }` for the
CALLER's org: a CLINIC-type org with an ACTIVE link → LINKED; a CLINIC-type org without → NONE; any other org → SAME_ORG.
The clinic never names the pharmacy, so it cannot address one it is not linked to.

**R2 · The medicine list (T1).** clinical `GET /api/clinic/consult/medicines` → LINKED: pharma internal
`GET /internal/linked-rx/medicines` (pharma asks auth R1 itself, then reads the PHARMACY's catalogue as that org) →
`[{id, name, formula}]` only; SAME_ORG: the screen keeps `ProductPicker` as today; NONE: `#clinRxNoLink`.

**R3 · Submit (T3–T6, T8).** LINKED → pharma internal `POST /internal/linked-rx` — pharma re-checks R1 (never trusts
clinical), writes in the **pharmacy's** org: source DOCTOR, `from_org_id` + `from_org_name` **stamped at write**,
`externalRef = clinic-<org>-enc-<id>`, **no diagnosis** (D3), MRN in notes, **no partyId** (the pharmacy's own bridge
matches by name + phone), **no storeId** (every branch of the pharmacy). Product check against the PHARMACY's catalogue.
NONE → refused `RX_NO_LINKED_PHARMACY` (code + key). SAME_ORG → unchanged.

**R4 · Hand-written lines (T2, D2).** `product_id` nullable in clinical (V9) and allowed by pharma for a LINKED
prescription only; the screen offers **Add as written** (`#clinRxAddFree`) and marks the line. The pharmacist chooses
the product with the existing **Change** (amend already accepts a new product id, `PrescriptionAmendService:91`);
Dispense already skips a line without a product (`DispenseService:70`).

**R5 · Status back (T7, D5).** pharma internal `GET /internal/linked-rx/{id}` (+ amendments) — only when
`from_org_id` = the caller's org; lines and status, never price or stock. Readable after the link ends (it is the
clinic's own prescription); new Submits are not.

**R6 · The pharmacy screen.** `.rx-from-clinic` "From clinic <name>" on the row (from the stamped name); the
hand-written line shows "choose the product".

**Data:** pharma **V10** `prescriptions.from_org_id BIGINT NULL, from_org_name VARCHAR(200) NULL`, index
`(from_org_id, id)` — InnoDB check first (MyISAM trap). clinical **V9** `encounter_rx_item.product_id` NULL.
Recheck both numbers before deploy (parallel sessions).

**Readers of `prescriptions` (to classify in the build trace):** list / search / count / get / dispense / amend /
cancel — all org-scoped → see the new row as the pharmacy's (WANT). Party bridge → by name/phone (WANT). The H4 store
filter → `store_id NULL` = every branch (WANT).

**Order — two vertical slices on ONE gate** (`docs/hc-1d-rx-guide-screens.cy.js`, X1–X6):
- **HC-1d-1** R1–R3, R6 for listed medicines → X1, X2, X3 (listed line), X4, X6 green.
- **HC-1d-2** R4 + R5 → X3 (hand-written), X5 green.
Plus `healthcare/hc-1d-rx-api.cy.js` (refusals: an unlinked clinic; a clinic reading another clinic's prescription; a
shop calling the route; the counter path claiming "from clinic").

**Deploy:** auth-service, clinical-service, pharma-service, monolith.

## HC-1d-1 BUILT (2026-10-16, user "go ahead") — not compiled / deployed
| Layer | Change |
|---|---|
| auth | `OrgLinkService.route(org)` (SAME_ORG / LINKED / NONE; a suspended pharmacy = NONE); `InternalLinkController` `GET /api/auth/internal/links/route` — secret + org from `X-Org-Id` ONLY, fails closed (404); SecurityConfig permits `/api/auth/internal/**`; `OrgLinkServiceTest` +4 |
| pharma **V12** | `prescriptions.from_org_id`, `from_org_name` (stamped), index `(from_org_id, id)` — InnoDB, live at V11 on 10-16 |
| pharma | `PrescriptionService.createFromLinkedClinic` (pharmacy org, key `clinic-<org>-enc-<id>`, no diagnosis / party / branch / user) + `getForLinkedClinic` / `pharmacyOfLinked` (only rows carrying the reader's clinic id); `validate` + `saveItems` shared with `create`; `LinkedRxService` (asks auth itself on every call; reads + checks the catalogue `runAs` the pharmacy; list rows stripped to id/name/formula; change history without `amendedBy`); `LinkedRxController` `/internal/linked-rx` (`clinic.consult`, refusal = 400 + `data.code`); `PartyBridgeService` bridges a linked row AS the pharmacy (found in the trace: it ran on the clinic's forwarded identity); `fromOrgName` on the DTO; `LinkedRxServiceTest` 6 + `PrescriptionServiceTest` +3 (Testcontainers — skip on Windows) |
| clinical | `LinkRouteClient`; `ConsultService.route()` (auth unreachable → refused, never "same org"), `medicines()`, `submitLinked`, `pharmacyCopy` reads the linked copy for a separate clinic; `RxRefusedException` `RX_NO_LINKED_PHARMACY` (400 + `data.code`, `ClinicalAccessAdvice`); `GET /api/clinic/consult/medicines`; `ConsultServiceTest` +5 |
| monolith | `GET /clinic/consult/medicines` proxy; `clinicDashboard.html` `#clinRxNoLink`, `#clinRxTo`; `clinic.js` route-aware list (`applyRoute`, "Sent to <pharmacy>"); `pharma.js` `.rx-from-clinic`; 6 keys × 6 bundles |
| specs | `docs/hc-1d-rx-guide-screens.cy.js` X1–X6 (X3 hand-written step + X5 stay red until HC-1d-2); `seedProduct` passes `formula` |

**Found in the trace, NOT fixed here (needs its own decision):** the gateway stamps the internal secret on EVERY
authenticated request and routes `/api/auth/**` and `/api/<svc>/internal/**` (StripPrefix) — so "has the secret" does
not mean "is a service". HC-1d's endpoints are built to be safe under that (org from the stamped header only, permission
checked). Existing ones to review: `PortalAccountController` (`/api/auth/portal/account` takes `organizationId` in the
BODY), business `/internal/sales` (checks body org = authenticated org), others under `/internal/**`.

## Decisions 2026-10-16 (the user) — HC-1d-3 / HC-1d-4
| # | Decision |
|---|---|
| (1) | Every prescription carries the **doctor's id** (the clinic's doctor record, `clinic_provider`, never a login id) and the **clinic's id** (`from_org_id`, built in HC-1d-1), with names stamped at write, so the pharmacy can report by doctor and by hospital/clinic. A doctor in reports = (clinic id, doctor id). The combined org stamps the doctor id too. |
| (c) | The doctor's **licence number** (e.g. PMDC) is **optional**: a field on the doctor form, stamped on each prescription when present. |
| (a) | Auto-display: **smart polling every 60 s** (the user: "increase to 1 minute if it is best to improve performance"), only while the tab is visible, plus an immediate check when the tab becomes visible again. One indexed "anything new after #id?" query; nothing returned when nothing is new. |
| (b) | **Sound, with a setting to switch it on/off (off by default)**: (i) at the pharmacy when a new prescription appears; (ii) in the clinic when the doctor presses **Call** / **Next** (the waiting room hears the token called). Each business's own setting. |
| (d) | Order confirmed: HC-1d-1 gate → **HC-1d-2** (hand-written lines; status back built in 1d-1) → **HC-1d-3** (doctor id + licence, reports by doctor / clinic) → **HC-1d-4** (auto-display + sounds). Each: Test Book cases first. |
