# R4 — guarantors and witnesses on an installment plan: analysis

**Status:** ANALYSIS, shared for review. No design, no code.
**Asked for by** Shahzad Mobile Shop: *"on installment there will be new form of vitnesses/guaranteed with
CNIC, Mobile Number, name and address with selection of customer from dropdown."*
**Follows:** [`installment-dues-reminders-design.md`](../installment-dues-reminders-design.md) · INST-1's
`installment_plan` (V42).

Live figures read from the Docker MySQL on 2026-09-04. Every count below is a real query.

---

## 1. The problem, in one sentence

**A shop that finances a handset has no record of who else stands behind the debt.** 211 live plans carry money
owed; not one names a guarantor, because there is nowhere to put one.

---

## 2. What already exists — and the surprise

### The decision this slice would have opened has already been taken, twice

`InstallmentPlan.java`:

> `/** A guarantor is a Party with a role, not a new entity — party-service already models this. */`
> `@Column(name = "guarantor_party_id") private Long guarantorPartyId;`

`V42__installment_plan.sql:63`:

```sql
guarantor_party_id  BIGINT  DEFAULT NULL,   -- a Party with a role; party-service owns it
```

So "party-service `Party` or a local table?" is **not an open question** — it was ruled on when INST-1 shipped,
and the column is already in the schema. What it is, is **a stub wired to nothing**: a repo-wide grep finds
exactly two references, both of them the declaration above. No service writes it, no screen offers it, no
report reads it.

### Measured

| | |
|---|---|
| installment plans | **297** |
| live plans (ACTIVE + DEFAULTED) | **211** |
| **plans naming a guarantor** | **0** |
| customers | 2,545 |
| **customers with a CNIC recorded** | **10** |
| customers bridged to a party | 2,275 (89%) |
| party rows · role links | 1,999 · 2,687 |
| roles in use | CUSTOMER, DONOR, PATIENT, STUDENT, VENDOR |

Two things fall out of that table:

* **`Customer.cnic` already exists** (`VARCHAR(20)`) and is essentially unused — 10 rows in 2,545. The field
  Shahzad is asking for is on the customer master already; nobody has been asked to fill it in.
* **`GUARANTOR` would be the first new role** since the party model was built, and the 89% bridge rate means
  most guarantors picked from the customer dropdown already have a party to point at.

---

## 3. The three things that are genuinely open

### 3a. One guarantor, or several? — the column says one, the request says several

Shahzad wrote *"vitnesses/guaranteed"*, plural, and Pakistani installment practice is commonly two: a
guarantor who is liable and a witness who is not. `guarantor_party_id` is a single column, so honouring the
request means either a second column (`witness_party_id` — a name that will be wrong for the third case) or a
link table.

**These are not the same role.** A guarantor is recourse; a witness attests. Collapsing them loses the only
distinction that matters when a plan defaults, and a shop that recorded a witness believing they had recourse
is worse off than one that recorded nobody.

### 3b. Where does the CNIC live? — Party has no column for it, and the role link is forbidden from holding one

```
party:            id · organization_id · party_type · name · contact · email · address · notes · …
party_role_link:  id · organization_id · party_id · module · role · local_id · label
```

There is no CNIC anywhere in party-service, and `PartyRoleLink`'s own javadoc closes the obvious shortcut:

> *"Never holds domain data: `label` is a display caption only, so the view can say 'also a pharmacy patient'
> without party-service learning anything clinical."*

So the options are a new `party.cnic` column (a national identifier is arguably a party attribute, like
`contact`), or keeping it in business-service beside the plan. The precedent cuts both ways:
`appointment-service`'s `Attendee.cnic` is a plain local `String`, and `Customer.cnic` is local too — but both
of those predate the party master, and neither was a deliberate ruling.

⚠ **A CNIC is not a phone number.** `party.contact` is documented as *"the primary de-dup key within an org"*.
A national ID is a far stronger identity claim, and putting it in a shared master means one tenant's data
entry becomes another tenant's match — party rows are org-scoped, so this is containable, but it should be a
decision rather than a side effect.

### 3c. ⚠ The party bridge is deliberately best-effort. A guarantor is legal recourse.

`TradeClientsConfig` bounds the party call at **1s connect / 2s read**, and says why:

> *"a SLOW party-service fails fast to best-effort (the bridge is off the domain tx + retried on next write)."*

That is exactly right for a customer bridge: the customer is already saved in business-service, and the party
row is an index that can catch up. **It is exactly wrong for a guarantor**, because the guarantor exists
*only* as a party. If party-service is slow while a plan is being written, the current pattern would commit
the plan with `guarantor_party_id = NULL` — a financed handset with no recorded guarantor, saved successfully,
with nothing on screen saying so.

This has the shape of two defects this codebase has already paid for:

* [`project_gl_outbox_drops_new_fields`] — a new field that vanishes silently because one of five places was
  not updated.
* ONB-2's `installmentsDue` — money owed disappearing from a screen while the debt stayed collectable.

**The rule those produced applies here: a record that exists to be relied on must not be able to go missing
quietly.** Either the guarantor write is inside the same transaction boundary as the plan (and a party-service
outage refuses the plan, which is harsh but honest), or it is best-effort **and the plan visibly says
"guarantor not recorded"** until it succeeds. What it must not do is look complete.

---

## 4. What I would build

| | Work | Why |
|---|---|---|
| **4a** | **A `plan_guarantor` link row** — plan, party, `role` (GUARANTOR / WITNESS), captured name+CNIC+contact+address as they stood | §3a: two roles, N rows, and the singular column becomes the first row rather than a special case |
| **4b** | **Stamp the identity at write**, do not derive it on read | The shop's evidence must be what the guarantor signed, not what that party's row says two years later after somebody edited it. This is [[feedback_stamp_at_write_not_derive_on_read]] applied to a legal record |
| **4c** | **The dropdown picks an existing customer; the fields stay editable** | 89% of customers already have a party, so the common case is two clicks. A guarantor who is not a customer is typed in and becomes a party with the GUARANTOR role |
| **4d** | **The plan shows whether a guarantor was recorded**, including when the bridge failed | §3c — the whole point |

**Deliberately not proposed:** a guarantor *portal*, guarantor-facing reminders, or any liability calculation.
The ask is a record, and INST-4's SMS transport is already blocked on the customer's provider decision.

---

## 5. Performance and security

**Performance.** One extra write per plan created, on a screen a shop uses a few times a day. The dropdown
reads customers the sale screen already loads. Nothing lands on the sale hot path.

**Security.** A CNIC is personal data under any reading. It must be org-scoped like every other read
(`ARCHITECTURE-MULTITENANCY.md`), never returned by a public or storefront endpoint, and — unlike a phone
number — it has no business appearing in a picker's search results, because that would let one tenant's staff
enumerate identifiers by typing digits. **Search the dropdown by name and phone, never by CNIC.**

---

## 6. Questions before I design

**Q1 — one row per plan, or several with a role?** *Recommendation: several, with `GUARANTOR` and `WITNESS`.*
It is what was asked for, and collapsing the two loses the only distinction that matters at default.

**Q2 — does the CNIC go on `party`, or stay in business-service beside the plan?** *Recommendation: stamp it
on the plan's guarantor row (4b), and leave `party` alone for now.* The evidence a shop needs is what was
recorded that day; making `party.cnic` the source of truth means a later edit rewrites history. This can be
revisited if a second module ever needs the identifier.

**Q3 — if party-service is unreachable while a plan is being written, refuse the plan or save it marked
incomplete?** *Recommendation: save it, and say so on the plan and in the list.* Refusing blocks a sale at the
counter for a reporting dependency, which is the mistake ONB-3 was careful not to make — but the plan must
carry a visible "guarantor not recorded" state, and it must be findable, or it is the silent-drop defect again.

**Q4 — should an existing plan be able to gain a guarantor afterwards?** *Recommendation: yes.* 211 live plans
have none, and refusing to record one retrospectively would mean the feature only ever applies to future
sales — which is the same reason there is no backfill.
