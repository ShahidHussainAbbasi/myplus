# Slice 44 — Pharmacy P7: dispense safety (rxRequired · controlled-substance · interactions)

Phase 2 pharmacy, after P6 (dispense). The clinical value-add: at dispense time, surface/enforce safety — a
medicine that **requires a prescription**, **controlled substances** (flag + log), and **drug interactions** among
the dispensed items. Net-new clinical, keyed by **`itemId`** (the bridge), reusing the dispense flow from P6.

## Model (pharma-service, net-new, org-scoped, itemId-keyed)
- **`MedicineClinical`** — `organizationId`, `userId`, `itemId` (unique per org), `rxRequired`, `controlledSubstance`,
  `drugCategory`. The lightweight clinical layer on an existing business Item (no parallel product model).
- **`DrugInteraction`** (exists) — `itemId1`, `itemId2`, `severity`, `description`, `recommendation`. + repo finder.

## Safety check
`SafetyService.check(itemIds, org, user)` → `SafetyReport { rxRequiredItems[], controlledItems[], interactions[] }`:
- `rxRequiredItems` / `controlledItems` from `MedicineClinical` (flagged, scoped).
- `interactions` = `DrugInteraction`s whose both items are in the set (scoped).

## Wire-in
- `POST /safety/check {itemIds}` → `SafetyReport` (pre-dispense warning).
- **Dispense** (P6) calls it: the dispense response carries `warnings` (controlled + interactions); controlled
  dispenses set `Dispensing.controlled` + are logged. rxRequired is satisfied because a dispense always references a
  prescription (the enforcement point).
- Clinical data setup: `POST /clinical` (upsert flags per itemId), `GET /clinical` (list), `POST /interactions` (add).
- monolith proxies: `/checkSafety`, `/saveClinical`, `/getClinical`, `/addInteraction`.

## UI (PHARMA-only, minimal, reuse)
- A small **Clinical** panel: list items (reuse `getUserItems`) with `rxRequired`/`controlled` checkboxes + an
  interactions add form.
- At dispense, before/after Complete Sale, call `/checkSafety` with the cart itemIds and **show warnings** (controlled
  + interactions) via the existing toast/error helpers.

## Tests
- pharma `SafetyServiceTest` (Testcontainers): flagged controlled/rxRequired returned; interaction returned only when
  both items present; org-scoped.
- Cypress (headed): flag an item controlled + add an interaction → `/checkSafety` returns them; dispense response
  includes the warning.

## Status
- [x] Design (this doc)
- [x] `MedicineClinical` + repo · `DrugInteraction` org-scoping + `DrugInteractionRepository` · `SafetyService`
  (check/upsertClinical/listClinical/addInteraction/isControlled) · `SafetyController` (`/safety/check`,`/clinical`,
  `/interactions`) · `SafetyServiceTest` (Testcontainers) · monolith `PharmaSafetyController` proxies
- [x] Dispense wire-in: `Dispensing.controlled` flagged from clinical flags
- [x] **Backend Cypress GREEN (headed, 2026-06-26): `safety.cy.js` 2/2** — checkSafety reports controlled+rx+interactions; interaction needs both items
- [x] UI: **Clinical & Safety** panel (PHARMA-only — flags + interactions, reuses `getUserItems`) + safety warnings
  surfaced at dispense (`checkSafetyForItems`)
- [x] **Cypress GREEN (headed, 2026-06-26): `safety.cy.js` 3/3** (checkSafety report; interaction needs both; Clinical panel renders). **P7 DONE.**

> Minor follow-up: `addInteraction` always inserts — a repeated identical pair creates duplicate rows (admin action; dedupe later).
