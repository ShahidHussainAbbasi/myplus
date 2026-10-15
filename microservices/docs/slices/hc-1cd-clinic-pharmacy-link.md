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
