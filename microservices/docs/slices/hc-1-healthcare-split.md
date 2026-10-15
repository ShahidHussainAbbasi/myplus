# HC-1 — Healthcare: Hospital/Clinic and Pharmacy as separate, linked tenants · standards §1e V5–V7

Status: **DIRECTION AGREED with the user (2026-10-15); slices below to be designed one at a time.**
First customer: **TAHSEEN HEALTHCARE CENTRE** — owner Dr Tahseen Fatima — registered TWICE, one email per tenant:
*Tahseen Healthcare Centre* (Hospital / Clinic) and its pharmacy (Pharmacy & Pharmaceutical).

## Decisions
- Product line **Healthcare**, business types **Hospital / Clinic** and **Pharmacy & Pharmaceutical**.
- The COMBINED model (clinic inside a pharmacy tenant) is DROPPED — "the UI/UX does not look professional".
- A clinic SELECTS an already-registered pharmacy on maxtheservice.com as its pharmacy → a LINK between two tenants.
- One email = one tenant owner (two emails for Dr Tahseen); one login across tenants later.

## Measured today (dev, 2026-10-15)
- The clinic lives INSIDE the pharmacy tenant: org 15 `owner.pharma@` (type PHARMA, shape pharmacy) has the clinic
  feature ON and **146 clinical encounters**; org 13 is entitled but has it off; the other pharmacy-shaped orgs are
  leftover onboarding test tenants (≈50 — `ONB Pharmacy/Switch/Ceiling …`) with no clinical data.
- Prescriptions move clinic → pharmacy inside one tenant today; nothing crosses tenants anywhere in the platform.
- Signup, operator provisioning and team-add all refuse an existing email (`AuthService` :108, :166, :259).
- Production: not yet measured — a read-only preflight is needed before any production change.

## Slices (each: design → consent → gate red first → build → user deploys)
| # | Slice | What |
|---|---|---|
| HC-1a | Product line + business types | Healthcare with *Hospital / Clinic* and *Pharmacy & Pharmaceutical* at signup and operator provisioning; each business type gets its module list (Clinic: Reception, Queue, Doctors, Patients, Prescriptions, Clinic settings…; Pharmacy: Sale, Purchase, Register, Inventory, Pharmacy…) |
| HC-1b | A clinic tenant that stands alone | The clinic dashboard as its own professional application: no pharmacy, till or stock menus; its own landing, workspaces and overview |
| HC-1c | The link | Clinic searches opted-in pharmacies → sends a link request → the pharmacy ACCEPTS → either side can end it; audited both sides |
| HC-1d | Prescriptions across the link | A doctor's prescription is addressed to the linked pharmacy; the pharmacy sees ONLY prescriptions sent to it (patient name/MRN, medicines, prescriber) and dispenses them; status flows back (dispensed / partly / not available) |
| HC-1e | Moving off the combined model | Existing combined tenants (dev: org 15) — split into a clinic tenant + a pharmacy tenant with the link, data moved by owner; production preflight first |
| HC-1f | Tahseen onboarding | Two registrations (two emails), link, first prescriptions — the go-live checklist |

## Privacy (from day one)
Medical data crosses only as prescriptions ADDRESSED to the linked pharmacy; the pharmacy never browses the clinic's
patients and the clinic never sees the pharmacy's stock or prices. Every hand-off is in both tenants' audit trails.
