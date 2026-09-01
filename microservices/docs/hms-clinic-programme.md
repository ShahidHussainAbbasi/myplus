# Clinic / HMS programme — analysis and phased implementation plan

**Status:** ANALYSIS + PLAN. No code written, no schema changed. Awaiting consent per the standing
REVIEW → CONSENT → DESIGN → IMPLEMENT → TEST cadence.
**Client requirement:** extend an existing pharmacy tenant with a clinic / hospital management system —
reception intake → patient queue → doctor consultation → prescription → the connected pharmacy dispenses.

---

## 1. Verdict up front

**This is an extension, not a new product.** Roughly 55–60% of the requested system already exists in this
platform and is shipped: a clinic-shaped scheduling service, a prescription and dispensing chain, a drug
safety engine, a shared party master that already knows the word `PATIENT`, per-tenant capabilities, audit,
notifications and money. The genuinely new work is **the clinical record and the consultation itself** —
encounter, vitals, diagnosis, allergies, orders, results — plus the doctor's workspace to run it from.

Three things about the request need to be settled before any code, because they change the shape of the
build rather than its size:

1. **The clinical domain must not go into `appointment-service`.** That service was *just* generalised out of
   being a clinic (slice SCHED-1) into a neutral scheduling core that education now depends on. Putting
   encounters and diagnoses back into it re-creates the exact defect that slice paid to remove.
2. **The AI recommendation is a regulated feature, not a convenience.** How it is built decides whether this
   product is "clinical decision support" (exempt, shippable) or "a medical device" (certification, audits,
   a different business). §6.4 states the four rules that keep it on the right side, and they are design
   constraints, not policy text.
3. **Staff names are not a design input.** "Mr. Irshad at reception" is a *user holding the RECEPTIONIST
   role*; "Dr. Shahid Iqbal" is a *user holding DOCTOR plus a provider record*. The platform already forbids
   `if (organizationId == 24)` by name (`Capability` javadoc); the same rule applies here.

---

## 2. What I actually reviewed

Evidence, not inference — every claim below traces to a file I opened:

| Area | Files read |
|---|---|
| Scheduling / clinic core | `appointment-service` — `Booking`, `Attendee`, `Provider`, `Slot`, `Venue`, `AppointmentController`, `SchedulingController`, 5 Flyway migrations |
| Pharmacy | `pharma-service` — `Prescription`, `PrescriptionItem`, `Dispensing`, `MedicineClinical`, `PrescriptionController`, `SafetyController`, `DispenseService`, `PartyBridgeService` |
| Identity | `party-service` — `Party` (already carries `PATIENT` as a party type), `PartyRoleLink` |
| Catalog | `catalog-service` — `Product`, `ProductDTO` (rx / controlled / batch / serial flags), `ProductBarcode` |
| Tenancy | `common-settings` — `Capability`, `Shape`; `ARCHITECTURE-MULTITENANCY.md` |
| Auth | `auth-service` — `SetupDataLoader` (org types incl. `APPOINTMENT`, `PHARMA`), `AuthService` (JWT `activeOrgType`) |
| Supporting | `audit-service` `/api/audit/record`, `notification-service` `/api/notifications`, `finance-service` `GlController` / `PaymentController` |
| Prior art | `domain-lifecycle-audit.md`, `sched-1-scheduling-core.md`, `education-complete-programme.md`, `ai-navigation-design.md`, `capability-platform-design.md` |
| UI | `appointmentDashboard.html`, `doctor.html`, `hospital.html`, `businessDashboard.html` (prescription intake at §`#PrescriptionDiv`) |

---

## 3. Asset map — what the HMS reuses instead of building

| Requirement | Already exists | Reuse as-is? |
|---|---|---|
| Hospital / clinic entity | `Venue` (appointment-service) | ✅ yes |
| Doctor | `Provider` — name, speciality, fee, schedule, capacity | ✅ yes; add PMDC registration no. |
| Patient identity | `Party` with `partyType = PATIENT`, de-duped per tenant on `(org, contact)`; `Attendee` in appointment-service | ⚠️ two patient-ish tables today — see D-2 |
| Patient queue | `Booking` with `slotId` nullable "for a clinic QUEUE booking" | ⚠️ designed for, **not built** — no queue number, no status |
| Appointment / follow-up booking | `Slot` + `/api/scheduling` (neutral, idempotent, unique-constrained) | ✅ yes |
| Prescription | `Prescription` + `PrescriptionItem` (productId, dosage, frequency, duration, dispensedQuantity) | ✅ yes — but authored by the **pharmacist** today; the doctor becomes the author |
| Dispensing to the patient | `Dispensing` → POS sale/saga, batch + FEFO, controlled flag, invoice link | ✅ yes, untouched |
| Drug safety | `SafetyController` `/safety/check`, `DrugInteraction`, `MedicineClinical` (rxRequired, controlledSubstance, drugCategory) | ✅ yes — extend with allergy + duplicate-therapy |
| Medicine master | catalog `Product` (+ tax, batch, expiry, loose/pack, barcode) | ✅ yes — the formulary is the product master |
| Per-tenant feature switches | `Capability` × `Shape`, `org.cap.*` settings, `[data-capability]` in the DOM | ✅ yes — add a `CLINIC` shape and clinical capabilities |
| Roles & privileges | auth-service seeds roles/privileges; they travel in the JWT | ✅ yes — add RECEPTIONIST / DOCTOR / NURSE |
| Audit trail | `audit-service` `/api/audit/record` | ✅ yes — becomes the PHI access log |
| Reminders (follow-up) | `notification-service`, `common-notify` | ✅ yes |
| Money (consultation fee, panel billing) | `finance-service` AR/AP/GL + outbox; POS invoice path | ✅ yes |
| Document numbering | `org_document_seq` (V45) — serialised per-org allocation | ✅ yes — MRN, visit no., Rx no. |
| Multi-branch | multi-location stores/branches, role × location grants in the JWT | ✅ yes — a clinic with two sites is already solved |

**What does not exist at all:** encounter/visit, vitals, diagnosis coding, allergies, chronic conditions,
risk factors, clinical notes, examination findings, orders (lab / imaging / procedure / blood), results,
referral, oncology, document attachments, prescription templates, any AI call, patient-facing consent,
retention policy, and a clinical UI. That is the build.

---

## 4. Gaps in the *existing* pieces that this programme must close first

These are not new features — they are load-bearing defects in the parts being reused. Found in
`domain-lifecycle-audit.md` and confirmed in the code:

| # | Gap | Why it blocks the HMS |
|---|---|---|
| G-1 | `appointment-service` still reads the monolith `user` table | A clinical service cannot inherit a cross-database read; it also blocks the monolith decommission |
| G-2 | `appointment-service` has **no role model** — booking deletes are open | Reception and the doctor need genuinely different permissions on day one |
| G-3 | `appointment-service` is not on `common-settings` | Clinic hours, session length, queue policy have nowhere to live |
| G-4 | Only 3 Cypress specs on the whole vertical; no lifecycle gate | A regression in the queue would be invisible |
| G-5 | `Booking.dateTime` / `date` are `VARCHAR` from the legacy schema | Queue ordering and "today's list" cannot be done reliably on strings |
| G-6 | The controlled-drugs register lacks prescriber, Rx id and batch | The HMS *supplies* all three — this gap closes as a by-product (see P2) |
| G-7 | Reads across the platform are frequently ungated (~74 in education) | For PHI, an ungated read is a reportable breach, not a code smell |

**G-1, G-2, G-5 and G-7 are inside Phase 1's scope.** They are not a separate "phase 0" the client would
struggle to see value in — they are the price of the first working cycle being trustworthy.

---

## 5. Architecture decisions

### D-1 — A new `clinical-service` on :8097. The clinical record does not go into `appointment-service`.

`appointment-service` was deliberately generalised (SCHED-1) so a school could book a parents' evening
without becoming a hospital. It now owns *time*: providers, slots, bookings, capacity, conflict detection.
It must keep owning the queue — a queue **is** a booking — and must never learn what a diagnosis is.

```
appointment-service (:8085-ish, existing)   clinical-service (:8097, NEW)
──────────────────────────────────────      ─────────────────────────────
Venue    = clinic / hospital                Patient  (MRN, clinical identity)  → party_id
Provider = doctor                           Encounter (the visit)              → booking_id
Slot     = a bookable window                Vitals, Diagnosis, ClinicalNote
Booking  = a place in the queue  ───────────▶ Allergy, Condition, RiskFactor
           (status + queue number)          Order (lab / imaging / procedure / blood)
                                            Result, Referral, Template, Document
```

Rationale, in the platform's own terms: a cross-cutting capability gets its own service; a bounded context
does not get folded into a neighbour because the neighbour is nearby.

### D-2 — Patient identity: one party, one clinical record, one MRN

Three tables currently could claim to be "the patient": `Party` (shared identity), `Attendee`
(appointment-service), `Customer` (POS, for the pharmacy sale). The rule that resolves it is the one the
platform already applies to customers and vendors:

- **`party-service` owns identity** — name, phone, address, de-dup. `partyType = PATIENT`. Already supported.
- **`clinical-service.patient` owns the clinical identity** — `party_id`, **MRN**, date of birth, sex,
  blood group, next of kin, consent state. One row per party per tenant.
- **`Attendee` is retired into `party_id`** — the same bridge pattern `pharma-service` already uses
  (`Prescription.partyId`, stamped best-effort by phone). Not a big-bang migration: Phase 1 stamps
  `party_id` on new rows and backfills by phone, exactly as P3 of the pharmacy work did.

The pay-off is the one the client will notice: **the patient at the till and the patient in the consulting
room are the same person**, so the pharmacy sees the prescription and the dispensing history in one place.

**MRN is allocated from `org_document_seq`** (V45), never `MAX(id)+1`, and never before the transaction is
certain to commit — the "allocate late" rule from the per-org numbering work.

### D-3 — The queue is a `Booking` in queue mode; the encounter is a `clinical-service` row

`Slot`'s javadoc already promises this ("a clinic Booking has `slotId == null` and carries a queue number") —
the column simply was never added. So:

| Field | Where | New? |
|---|---|---|
| `queue_number` (per clinic, per doctor, per day) | `booking` | **new column** |
| `status` — `WAITING / CALLED / IN_CONSULT / COMPLETED / NO_SHOW / CANCELLED` | `booking` | **new column** |
| `checked_in_at`, `called_at`, `started_at`, `completed_at` | `booking` | **new columns** |
| The consultation itself | `clinical.encounter` (FK `booking_id`) | new table |

Booking status becomes a real state machine with legal transitions — that is G-2/G-5 closed and the
"booking-status lifecycle" the domain audit already asked for.

### D-4 — AI lives in its own `assistant-service`, and the clinic never holds an API key

`ai-navigation-design.md` already specifies `assistant-service → Claude` for the command palette. Reuse that
decision rather than making a second one: **one service owns every model call** — the credential, the rate
limit, the redaction, the cost accounting and the audit record. `clinical-service` asks
`assistant-service` for a suggestion and gets back structured data it must then validate. See §6.4.

### D-5 — The UI is a new `clinicDashboard.html` in the monolith, not a bolt-on to the trade dashboard

Two different jobs, two different screens: reception needs a fast intake + queue board; the doctor needs a
single-screen consultation workspace. Both proxy to `clinical-service` exactly as `businessDashboard.html`
proxies to catalog/inventory. This follows "build fresh, not port" and "improve the UI with the move" —
`appointmentDashboard.html` (288 lines, hospital/doctor CRUD only) is not a foundation for a clinical
workspace and should not be stretched into one.

### D-6 — A `CLINIC` shape and clinical capabilities, so this is a product and not a bespoke install

```
Shape.CLINIC  → preset: OPD_QUEUE, E_PRESCRIBING, CLINICAL_RECORD, plus the PHARMACY preset
                where the tenant also dispenses (Shahid Iqbal's case: pharmacy + clinic)
New capabilities: opdQueue · clinicalRecord · ePrescribing · labOrders · imagingOrders
                  · procedureOrders · bloodBank · oncology · referrals · aiSuggestions
                  · teleconsult (later)
```

`aiSuggestions` **defaults OFF**. Every other capability defaults per shape. A tenant that does not have the
capability never sees the control and the server refuses the write independently — the two-level rule
already enforced by `ProductService.requireCapability`.

---

## 6. Standards and regulation — what "complying" concretely means

This is a clinical system. The rules below are not a compliance appendix; four of them change the schema and
one changes the AI design.

### 6.1 Coding and terminology (decides column types, now — retrofitting is a migration)

| Domain | Standard | Where it lands |
|---|---|---|
| Diagnosis | **ICD-10** (ICD-11 ready — store the code system alongside the code) | `diagnosis.code_system`, `code`, `term` |
| Clinical findings | **SNOMED CT** (optional, phase 6+) | same shape as above |
| Lab tests & results | **LOINC** | `order_item.code_system = LOINC` |
| Drugs | **ATC** + the national registration number (**DRAP** in Pakistan) | catalog `Product` — add `atc_code`, `registration_no` |
| Procedures | CPT or the local schedule | `order_item` |
| Allergy substances | SNOMED / ATC class, never free text alone | `allergy.substance_code` + `substance_text` |

**Rule: every coded field stores `(code_system, code, display_text)`, never a bare string.** A coded field
without its system is unusable the first time a second system appears, and that is a data migration nobody
budgets for.

### 6.2 Interoperability — design to FHIR R4 now, expose it later

Do **not** build FHIR internally. Do map every clinical table to its FHIR R4 resource from day one so the
façade is later a translation and not a rewrite:

`Patient` · `Encounter` · `Condition` · `AllergyIntolerance` · `Observation` (vitals + lab results) ·
`MedicationRequest` (the prescription) · `MedicationDispense` (the pharmacy) · `ServiceRequest` (lab /
imaging / procedure) · `DiagnosticReport` · `DocumentReference` · `Appointment` · `Practitioner`.

For lab-machine integration, **HL7 v2 ORM/ORU** is still the real-world interface — that is a Phase 5
option, not a Phase 1 concern.

### 6.3 Privacy, access and the medico-legal record

| Requirement | Standard | Implementation |
|---|---|---|
| Every PHI **read** is logged — who, what, when, why | HIPAA §164.312(b) audit controls; GDPR Art. 30 | `audit-service` on reads, not only writes. **This is why G-7 matters** — an ungated read of a product is a bug; of a patient record it is a reportable breach |
| Minimum necessary / need to know | HIPAA; GDPR Art. 5(1)(c) | Reception sees demographics + queue, never clinical notes. The doctor sees their own patients' records. A named **break-glass** path exists, is loud, and is reviewed |
| Special-category data | GDPR Art. 9 | Explicit consent captured and stored with a timestamp and version |
| The record is append-only | Every jurisdiction | A clinical note is **never edited in place** — an amendment is a new version with author, timestamp and reason. Same for a signed prescription |
| Retention | Typically 7–10 years for adults; to age 25 for minors | `retention_class` on the record from day one; purge is a later job that needs the column to exist now |
| Prescriber identity on the prescription | PMDC (PK) / equivalent | `provider.registration_no` printed on every Rx — also closes **G-6** |
| Controlled drugs register | DRAP (PK) / equivalent | The existing register gains prescriber, Rx id and batch because the HMS finally knows them |
| Two patient identifiers before any clinical act | Joint Commission NPSG.01.01.01 | The doctor's "call next" screen shows **name + MRN + date of birth**, and the token alone is never sufficient |
| Tenant isolation | The platform's own multi-tenancy standard | `org_id` + `findScoped` + stamped writes + anti-IDOR, on every clinical table without exception |

### 6.4 The AI recommendation — four rules that keep this out of medical-device territory

The client's requirement is "AI produces a list of recommended medicines". Software that recommends a
specific treatment is, in the EU, a **Class IIa medical device under MDR Rule 11**; in the US it escapes
device regulation under the 21st Century Cures Act §3060 CDS carve-out **only if the clinician can
independently review the basis of the recommendation**. The difference between a shippable feature and a
certification programme is entirely in the design:

1. **Advisory and inert.** A suggestion is never a prescription. Nothing is written to the Rx until the
   doctor explicitly accepts each line. Nothing is pre-ticked.
2. **Show the basis.** Every suggested medicine displays *why* — the indication it maps to, the class, the
   dose rationale, and the patient factors considered. A recommendation the doctor cannot interrogate is the
   one that makes this a device.
3. **The model never has the last word on safety.** After the doctor builds the final list — from any
   source — the **deterministic** engine runs: allergy check, drug–drug interaction (`DrugInteraction`),
   duplicate therapy, `rxRequired`, controlled-substance rules, and (later) renal / paediatric dosing. The
   existing `SafetyController` is that engine. A model output that skipped it would be the single most
   dangerous thing in this build.
4. **Constrained to the tenant's own formulary, and validated server-side.** The model is given the
   tenant's catalog and asked to return product ids from it — and **every returned id is re-checked against
   the formulary server-side and silently dropped if it is not there.** A model that invents a product id
   must produce nothing, not a plausible-looking line.

Additional non-negotiables: **de-identify before the call** (age band, sex, weight, renal/hepatic flags,
allergies, current medicines, symptoms, working diagnosis — never name, MRN, phone or address), **log the
prompt and the response against the encounter** for medico-legal traceability, and **store what was
suggested versus what was prescribed** — which is also the only honest way to measure whether the feature
helps.

**Technical shape** (assistant-service, official Anthropic Java SDK — bindings to be read from the
`claude-api` skill's `java/` reference at implementation time, never guessed):

- Model **`claude-opus-5`**, adaptive thinking (`thinking: {type: "adaptive"}`); `budget_tokens` is rejected
  on this model. Start at `output_config.effort: "medium"` and measure.
- **Structured outputs** via `output_config.format` — the suggestion list is a schema, not prose to parse.
- **Prompt caching** with the system prompt + the tenant formulary as the stable prefix and the
  de-identified patient context after the last breakpoint; verify with `usage.cache_read_input_tokens`.
  A formulary of a few thousand products is exactly the payload caching exists for.
- **Streaming**, so the doctor sees the list forming rather than a spinner.
- Hard timeout with a clean fall-through: **if the model is slow or down, the screen still works** —
  templates and manual entry are unaffected. The AI is never on the critical path of a consultation.

---

## 7. The programme

Every phase below is a **complete cycle a real user can run end to end**, ships with a passing headed
Cypress gate, and leaves the system releasable. No phase depends on a later phase to be useful.

---

### Phase 1 — Reception → queue → consultation → closed visit

*The spine. After this phase the clinic genuinely runs, on paper prescriptions.*

**The cycle:** Irshad (RECEPTIONIST) registers a patient — or finds an existing one by phone — takes the
consultation fee, and the patient joins Dr Shahid's queue with a token. Dr Shahid (DOCTOR) opens his queue,
calls the next token, verifies **name + MRN + date of birth**, records the chief complaint and a note, and
closes the visit. The visit appears in that patient's history. Reception sees the queue advance live.

**Build:**
- `clinical-service` (:8097) — bootstrapped to the platform standard: Flyway, `org_id` scoping,
  `findScoped`, method authz, `common-settings`, audit client, OTel.
- Tables: `patient` (MRN, party_id, dob, sex, blood group, next of kin, consent, retention_class),
  `encounter` (booking_id, patient_id, provider_id, department_id, type, status, times, chief complaint),
  `clinical_note` (versioned, append-only), `department`.
- `appointment-service`: `booking.queue_number`, `booking.status` + timestamps, the status state machine,
  real `DATETIME` columns alongside the legacy strings (**G-5**), the role model (**G-2**),
  `common-settings` for clinic hours and queue policy (**G-3**), and the `user`-table read closed (**G-1**).
- auth-service: `ROLE_CLINIC_RECEPTIONIST`, `ROLE_CLINIC_DOCTOR`, `ROLE_CLINIC_NURSE`, `ROLE_CLINIC_ADMIN`
  + privileges. `Shape.CLINIC` and the clinical capabilities.
- Money: consultation fee collected at check-in through the **existing** invoice/receipt path → AR/GL.
- UI: `clinicDashboard.html` — **Reception** (register / find, check in, queue board) and **My Queue**
  (the doctor's list + the consultation screen).
- Audit: every read of a patient or encounter recorded (**G-7**).

**Standards satisfied:** MRN allocation, two-identifier verification, append-only notes, PHI access audit,
role separation, tenant isolation, retention class captured.

**Gate:** `clinic-queue.cy.js` — as `owner.clinic@`, then across the receptionist / doctor / admin ladder,
plus a cross-tenant case proving another clinic cannot see the queue. Reception must be **unable** to open a
clinical note; the doctor must be unable to change the fee.

---

### Phase 2 — Prescription → print → the connected pharmacy dispenses

*This is the requirement's headline: the patient walks to the counter and the medicine is already waiting.*

**The cycle:** During the consultation Dr Shahid picks medicines from the pharmacy's own catalog, sets dose
/ frequency / duration / quantity, signs the prescription, and prints or downloads a PDF. The prescription
lands in the pharmacy queue **electronically**. The patient walks to the counter; the pharmacist opens the
waiting Rx, and dispenses through the existing POS/saga sale — batch, FEFO, expiry, stock and invoice all
unchanged.

**Build:**
- `Prescription` gains: `encounter_id`, `provider_id`, `prescriber_registration_no`, `signed_at`,
  `version`, `status` extended with `ISSUED`. Authoring moves to the doctor; the pharmacist's intake screen
  stays for walk-in paper prescriptions from outside doctors.
- **Immutability**: once signed, an Rx is never edited — an amendment is version *n+1* with a reason, and
  the pharmacy is told.
- Prescription PDF: clinic letterhead, patient identifiers, prescriber name + registration number, date,
  each item with dose/frequency/duration/quantity, signature block, and a QR code resolving to the Rx for
  verification.
- Pharmacy: a "Prescriptions waiting" queue on the trade dashboard, filtered to today, one click to
  dispense.
- The safety engine runs **at signing**: allergy, interaction, `rxRequired`, controlled — a hard stop on
  contraindications with an explicit override that is recorded with a reason.
- **G-6 closes**: the controlled register now records prescriber, Rx id and batch.

**Standards satisfied:** e-prescription integrity and versioning, prescriber identity (PMDC), controlled-drug
register (DRAP), FHIR `MedicationRequest` → `MedicationDispense` mapping.

**Gate:** `clinic-prescription.cy.js` — doctor signs → the Rx appears in the pharmacy queue → dispense →
stock falls, invoice raised, register updated. Plus: an unsigned Rx is not dispensable; a signed Rx cannot be
edited; a contraindicated pair is refused (assert the **envelope**, since refusals arrive as HTTP 200 +
`success:false`).

---

### Phase 3 — The clinical record: vitals, allergies, chronic conditions, risk factors, history, follow-up

*Makes the second visit better than the first — which is the entire argument for a clinical record.*

**The cycle:** The nurse records vitals at intake (BP, pulse, temperature, SpO₂, weight, height → BMI
computed, with paediatric growth centiles where age applies). The doctor sees the patient's timeline —
previous visits, diagnoses, medicines, allergies, chronic conditions, risk factors — before saying a word.
A coded ICD-10 diagnosis is recorded. A follow-up is booked into the scheduling core and the patient gets a
reminder.

**Build:**
- `vitals` (typed and range-checked — a temperature of 370 is a typo, not a reading), `allergy`
  (substance + reaction + severity + criticality; **surfaced everywhere, permanently**), `condition`
  (chronic, with onset and status), `risk_factor` (smoking, alcohol, family history, occupational),
  `diagnosis` (coded, primary/secondary, with certainty).
- Patient timeline API — one call, one screen, no N+1.
- Follow-up → `/api/scheduling` + `notification-service` reminder.
- Allergy feeds the Phase 2 safety check; a hard allergy is a hard stop.

**Standards satisfied:** ICD-10 coding with `code_system`, FHIR `Observation` / `Condition` /
`AllergyIntolerance`, allergy prominence (a patient-safety requirement in every accreditation standard).

**Gate:** `clinic-record.cy.js` — vitals out of range refused; an allergy recorded in visit 1 blocks the
contraindicated prescription in visit 2; the follow-up reminder is queued.

---

### Phase 4 — Prescribing accelerators: templates, then AI

*The client's "3 sources", built in the order of risk: the deterministic ones first.*

**The cycle:** Dr Shahid saves a set of medicines he prescribes together as a named template
("URTI — adult"). Next time he types the symptoms or the diagnosis, the screen offers **(a)** his matching
templates, **(b)** the patient's current medicines to continue, and **(c)** — when `aiSuggestions` is on —
an AI-generated list, each line showing its basis. He accepts, edits, adds or removes freely. The final list
goes through the same safety engine as every other prescription.

**Build:**
- `rx_template` + `rx_template_item` — per doctor, shareable to the clinic, versioned. **Ships and gates
  first, on its own.** A template is deterministic, offline, and useful whether or not the AI is ever built.
- `assistant-service` (D-4): the Anthropic client, redaction, formulary injection with prompt caching,
  structured output, server-side formulary validation, per-tenant rate and cost limits, full request/response
  audit against the encounter.
- The suggestion panel: three clearly-labelled sources in one picker, **nothing pre-selected**, each AI line
  carrying its basis and a one-tap "why this?".
- Capability `aiSuggestions`, **off by default**, owner-enabled, with the disclaimer recorded per tenant.
- Telemetry: suggested vs prescribed, per diagnosis — the only honest measure of whether it earns its cost.

**Standards satisfied:** §6.4 in full — advisory only, basis shown, deterministic safety last,
formulary-constrained, de-identified, logged.

**Gate:** `clinic-rx-template.cy.js` and `clinic-ai-suggest.cy.js` — a template loads and is editable; with
the capability off the AI panel is `cap-off` **and** the endpoint refuses; a fabricated product id in a
stubbed model response is dropped, not rendered; the model being unreachable leaves the screen fully usable;
no patient identifier appears in the outbound payload (assert the recorded request).

---

### Phase 5 — Orders and results: lab, X-ray / imaging, procedures

**The cycle:** The doctor orders tests from the consultation screen. The order prints with the patient's
identifiers and a barcode. The lab or radiology desk sees a worklist, marks the sample collected, enters the
result (or attaches the report), and the result lands back on the encounter — flagged when out of range, and
the doctor is notified.

**Build:** `order` + `order_item` (LOINC / CPT coded, priority, clinical indication), `result` (value, unit,
reference range, abnormal flag, verified-by), `specimen`, worklist screens, result-entry screen, PDF report.
Panels (an "LFT" that expands into its constituent tests). Optional **HL7 v2 ORM/ORU** interface to an
analyser. Optional DICOM *pointer* for imaging — store the accession number and a link, never the pixels.

**Standards satisfied:** LOINC, reference ranges with units, critical-result notification (an accreditation
requirement), FHIR `ServiceRequest` / `DiagnosticReport`, result verification by a named person.

**Gate:** `clinic-orders.cy.js` — order → worklist → result → back on the encounter, with an abnormal flag
and a notification; an unverified result is visibly marked as such.

---

### Phase 6 — The specialty tail: blood bank, referral, medical records, oncology

Each is a self-contained cycle; **build only what the client actually needs**, in the order they ask.

- **Blood bank request** — request (group, components, units, urgency, indication) → crossmatch record →
  issue → transfusion outcome. Mandatory two-person verification before issue; full traceability from donor
  unit to patient (a legal requirement everywhere blood is transfused).
- **Referral** — outbound (to a specialist or hospital, with a clinical summary) and inbound, with status
  tracking. FHIR `ServiceRequest` / `Task`.
- **Medical records / documents** — scanned reports, consents, images, discharge summaries. Typed
  (`DocumentReference`), versioned, access-audited, virus-scanned, never served from a guessable URL.
- **Oncology** — this is the one to be honest about: protocol-driven regimens, cycle scheduling, BSA-based
  dosing and cumulative-dose tracking are a **specialist product**, not a screen. Recommend deferring until
  there is a real oncology user, and building the *record-keeping* subset (regimen, cycle, staging) first.

**Gate:** one spec per sub-domain, each proving its full cycle.

---

### Phase 7 — Money, panels and statutory reporting

**The cycle:** Consultation fees, procedure and lab charges, and the pharmacy sale all reconcile. Panel /
insurance patients are billed to the panel with a co-pay at the counter (the pharmacy already has
insurance/co-pay work in slice 59). Day-end closes. The registers a regulator can ask for print.

**Build:** service/charge master, encounter charge capture, panel master + claim, co-pay split, day-end
reconciliation, and the statutory outputs — controlled-drugs register, notifiable-disease report, PHI access
report, prescriber activity. GL posting through the outbox, minding the standing caveat that a new
`PostingEventRequest` field must be added in five places or it vanishes silently — **gate the trial balance**.

**Gate:** `clinic-billing.cy.js` — the money in the day-end equals the sum of the encounters and the
pharmacy invoices, with a panel patient in the set.

---

### Phase 8 (optional, demand-led) — FHIR façade, patient portal, teleconsult, IPD

Admission / bed management / IPD is a **second product**, not a phase of this one. Say so early: an OPD
clinic and an inpatient hospital differ far more than the words suggest, and promising IPD inside this
programme is how HMS builds fail.

---

## 8. Rules every phase obeys

1. **Tenant isolation on every clinical table** — `org_id`, `findScoped`, stamped writes, anti-IDOR on every
   read by id. No exceptions, and PHI raises the cost of an exception from a bug to a breach.
2. **Every read of PHI is audited**, not only writes.
3. **Coded fields store `(system, code, text)`.**
4. **Clinical data is append-only.** Amend, never overwrite. Every row carries author + timestamp.
5. **The deterministic safety engine runs last**, on the final list, whatever produced it.
6. **Capability + shape gating**, server-enforced independently of the UI.
7. **Document numbers from `org_document_seq`, allocated late.**
8. **Every slice ships a passing headed Cypress gate**, run as the clinic's own tenant *and* across the
   privilege ladder, plus a cross-tenant negative case.
9. **Flyway per service, deploy-reproducible**, no manual step.
10. **No client name, no org id, in any branch.**

---

## 9. Decisions needed from the client before Phase 1

| # | Question | Why it blocks |
|---|---|---|
| Q-1 | One organisation for the pharmacy + clinic, or two linked organisations? | Decides whether the prescription hand-off is in-tenant (simple) or cross-tenant (a consented data-sharing agreement, and a different security model) |
| Q-2 | Country and regulator — Pakistan (DRAP / PMDC / provincial healthcare commission)? | Fixes the prescription's legal content, the controlled register format, and retention periods |
| Q-3 | MRN format — per clinic, per organisation, or a national ID? | Cannot be changed after the first patient without a migration |
| Q-4 | Does the clinic bill panels / insurers, or cash only? | Moves Phase 7 earlier if panels are day-one |
| Q-5 | ICD-10 licensing and the code set to load | Phase 3 needs the file |
| Q-6 | Is `aiSuggestions` wanted at launch, and does the client accept the advisory-only constraint (§6.4)? | Decides whether Phase 4 is one slice or two |
| Q-7 | How many doctors and rooms, and does more than one doctor share a queue? | Queue policy and the "call next" rules |
| Q-8 | Data residency — is a cloud outside the country acceptable for PHI? | Affects hosting and the AI call |

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| Scope: "HMS" is often heard as "including inpatient" | Phase 8 states IPD is a separate product. Agree it in writing before Phase 1 |
| The AI feature drags the product into device regulation | §6.4's four rules are build constraints; the gate asserts them |
| PHI breach through an ungated read | Rule 2 + the audit gate; G-7 is closed in Phase 1, not "later" |
| `appointment-service` holds live data — 14 venues across 2 organisations at last count | The same staged approach SCHED-1 used: additive columns, no behaviour change, count before acting |
| Clinical safety: a wrong dose or a missed allergy | The deterministic engine is mandatory and last; hard stops need a recorded override reason |
| The doctor's screen is too slow to use in a 6-minute consultation | The timeline is one call; the AI is off the critical path with a hard timeout |
| A second product (oncology, IPD) hides inside a phase | Both are called out explicitly and deferred to real demand |

---

## 11. Recommended sequence

**Phase 1 → 2 first, and ship them.** Together they are the complete requirement as the client described it:
reception, queue, consultation, prescription, pharmacy. Everything after that is depth, and the client can
choose the order from real use rather than from this document.

**Effort shape** (relative, not a quote): Phase 1 is the largest single piece because it carries the new
service, the role model and four inherited gaps. Phase 2 is small — it is mostly re-pointing an existing
prescription chain at a new author. Phase 3 is medium. Phase 4's templates are small; the AI slice is medium
and mostly non-functional work. Phases 5–7 are each medium and independent.

---

## Progress log

| Date | Entry |
|---|---|
| 2026-08-31 | Review + programme written. No code, no schema. Awaiting consent and the Q-1…Q-8 answers. |
