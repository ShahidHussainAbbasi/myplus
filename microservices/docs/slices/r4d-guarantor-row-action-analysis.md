# R4d — a per-plan Guarantors action on the installments list

**Status:** ANALYSIS ONLY, for review. No design, no code — per `CLAUDE.md` RULE 0 and the
consent-before-changes rule.
**Ask:** *"on tableInstallment there on every plan there should be option like `openContact360(1271)` where
user click on `openGuarantor(planId)` to show the Guarantors where user can also edit the Guarantors on that
plan for that specific customer."*

Everything below was read from the code on 2026-09-08, not recalled.

---

## 1. Verdict up front

**The row action is easy. "Edit" is not, and it is the whole of this slice.**

There is no update path for a guarantor anywhere in the product — no endpoint, no `id` parameter, no
`updatedAt` column. The record was deliberately built as a **stamped, append-only** one. So "edit the
guarantors" is a product decision about what a guarantor record *is*, not a screen to be wired up. §4 puts
three options to you.

One thing to settle first: **R4c already renders a guarantors panel** under the plan's schedule (shipped, gate
written, awaiting the container rebuild). Adding a modal as well would give the same data two surfaces to
drift between. §6 recommends which one survives.

---

## 2. What exists today

| Piece | State |
|---|---|
| `GET /planGuarantors?planId=` | ✅ exists, org-scoped from the TOKEN, proxied |
| `POST /savePlanGuarantor` | ✅ exists — **INSERT only**, no `id` parameter |
| `POST /deletePlanGuarantor` | ✅ exists — `OWNER_ONLY` |
| **An UPDATE endpoint** | ❌ **does not exist** |
| `PlanGuarantor` columns | `createdAt`, `createdBy` — **no `updatedAt` / `updatedBy`** |
| R4c panel (list + add + remove) | ✅ built, under the schedule |
| A row action on `tableInstallment` | ❌ none — the whole row is clickable and opens the schedule |

`tableInstallment` has **10 columns** (Plan · Customer · Invoice · IMEI · Financed · Paid · Remaining ·
Next due · Overdue · Status) and no actions column.

---

## 3. The pattern you named is the right one

`contact360Button(partyId)` / `openContact360(partyId)` in `/js/common/party-contact.js` is a good model, and
worth copying properly rather than loosely:

* **It returns `''` when the viewer may not see it** — the row action gates itself, so no call site has to
  remember to.
* **It builds the panel with `document.createElement` and `textContent`, never `innerHTML`** — deliberately,
  and it matters more here: a guarantor's name and address are free text a cashier typed, and this is exactly
  the injection surface `dom-safe.js` exists for.
* **Esc closes, the backdrop closes, focus returns to the opener** — accessibility that a hand-rolled modal
  usually misses.
* **The name is not passed in the onclick** — the panel fetches what it shows, so nothing has to be escaped
  into an HTML attribute.

⚠ **One trap specific to this table:** the plan row already has `.on('click', … showSchedule(p))`. A button
placed inside it fires *both* unless the handler calls `event.stopPropagation()` — the modal opens and the
schedule reloads underneath it.

---

## 4. ⭐ The real question: what does "edit" mean here?

R4's own service doc states the principle: *"The identity is **stamped**, never derived on read — the shop's
evidence is what was signed."* A guarantor row is the shop's recourse when a plan defaults. Editing one
changes the evidence after the fact.

Today the only way to change a guarantor is **delete + add**, which has three consequences worth seeing:

1. **`createdAt` silently re-dates to today.** A guarantor recorded in March, corrected in September, now
   reads as recorded in September. If a dispute ever turns on when somebody agreed to stand behind a debt,
   that is the field it turns on.
2. **A cashier cannot do it.** `deletePlanGuarantor` is `OWNER_ONLY`; `savePlanGuarantor` is **ungated**. So
   the current shape lets a cashier ADD a guarantor but not correct a typo in one.
3. **There is no trace.** Nothing records that a row was replaced, or by whom.

### The three options

| | What it is | Cost | Honest about |
|---|---|---|---|
| **A. True update** | add `id` to save, plus `updatedAt`/`updatedBy` columns and a Flyway migration | one migration, one endpoint, privilege decision | ⚠ overwrites the signed record in place — the old value is gone |
| **B. Correct by superseding** ⭐ | new row replaces old; the old is kept, marked superseded, hidden by default | a `superseded_by` column + a filter on the read | ✅ the evidence trail survives; "what was signed" is still answerable |
| **C. No edit — add/remove only** | what R4c ships today | nothing | ✅ simplest, but a typo can only be fixed by an owner deleting and re-adding |

**My recommendation: B**, and if that is more than the shop needs right now, **C plus fixing the privilege
asymmetry** — because A quietly destroys the one property the record was built to have.

If you pick **A** anyway, it should at least carry `updatedAt`/`updatedBy` so the change is visible; an
in-place edit with no trace is the only variant I would argue against.

---

## 5. The privilege asymmetry, whichever option wins

`savePlanGuarantor` carries **no `@PreAuthorize` at all**, while `deletePlanGuarantor` is `OWNER_ONLY` with
the comment *"a guarantor record is the shop's recourse, and removing one is not a cashier's decision."*

Both statements cannot be right. Either recording who stands behind a debt is a cashier's job (in which case
add stays open) or it is not. Worth a ruling in the same pass, because "edit" inherits whichever answer you
give — and under delete+add, edit currently requires OWNER while add does not.

---

## 6. ⚠ Two surfaces for one fact

R4c put the guarantors panel **under the schedule**, on the reasoning INST-5a used for the IMEI and the
repossess action: it is the screen a shopkeeper is already on when a plan goes wrong.

Your ask puts them in a **modal from the row**. Both are reasonable; having both is not — the DRY rule in
this project is explicit, and two renderers for one record is how they come to disagree.

Three ways to settle it:

1. **Modal only** — delete R4c's inline panel, move it into `openGuarantor(planId)`. Consistent with
   Contact-360, and reachable without opening the schedule first. *Costs: the panel and its gate are rewritten.*
2. **Inline only** — the row button just opens the schedule and scrolls to the panel. Cheapest; one renderer.
3. **⭐ Shared renderer, two hosts** — one `renderGuarantors(planId, targetEl)` used by both the modal and the
   inline panel. Slightly more work now, no drift later. *This is what I would do.*

---

## 7. What I would need from you to proceed

1. **Which edit model** — A, B, or C (§4).
2. **Which surface** — modal, inline, or shared renderer (§6).
3. **Who may add / edit / remove** — is recording a guarantor a cashier's job? (§5)

Once those three are answered the work is small and mostly mechanical: an actions column on
`tableInstallment`, a `guarantorButton(planId)` that self-gates, `stopPropagation` on the click, and — for
option A or B — one endpoint plus one Flyway migration.

## 8. What I did NOT check

* **Whether any tenant has actually mis-typed a guarantor.** The case for edit is assumed from the request,
  not measured. If it is rare, option C costs nothing and answers it.
* **Mobile layout of an 11th column.** `tableInstallment` is already wide; the responsive contract wraps
  grids, but an actions column on a phone may want the row-menu treatment other grids use.
* **Whether the modal should also show the plan's customer** (name/phone) alongside the guarantors — likely
  wanted, since the question being asked is "who can I call about this plan?", and the buyer is part of that
  answer.
