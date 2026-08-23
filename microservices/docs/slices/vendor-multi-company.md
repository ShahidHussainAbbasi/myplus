# A supplier can represent several brands

**Status: DONE + GREEN 2026-08-23** — `vendor-multi-company.cy.js` 5/5, business-service unit 179/0/0 skipped.

---

## The limitation removed

`vender.company_id` was a single FK, so "Shahzad Mobile Shop" could be registered as the Nokia distributor or
the Samsung one, never both. The shop's workaround was to create the same supplier twice — which splits their
payables across two rows and makes the statement understate what is owed to one business.

## Why it was a small change

The link is **descriptive, not load-bearing**. Verified before writing anything:

* `VenderRepository.findByCompanyId` existed and was called by **nothing**;
* `Purchase`'s own company mapping is commented out — purchases never referenced a company;
* nothing filters, reports, ages or posts by a vendor's company.

So there is no ledger or reporting consequence. The only readers are the vendor grid and the vendor form.

## What shipped

| | |
|---|---|
| `V47` | `vender_company` join table + backfill of all 94 existing suppliers |
| `V48` | makes the superseded `vender.company_id` NULLable |
| Entity | `@ManyToMany(EAGER)` — both readers need the names every time, and the grid loads all vendors at once |
| DTO | `companyIds` (CSV) + `companyNames` (display) |
| UI | bootstrap-select `multiple`, Select All/Deselect All, collapses to "3 companies selected" past two |

## Four findings from the review, each of which would have shipped broken

**The monolith proxy flattens repeated parameters.** `params.put(k, v[0])` keeps only the first value, so a
native multi-select posting `companyIds=1&companyIds=2` would have arrived as `1` — silently losing brands.
`populateFormData()` already joins a multi-select's values with commas into ONE parameter, which survives
intact. That is why the DTO takes a **string**, and it must not be "tidied" into a `List`.

**`editRecord` looked multi-select aware and was not.** It split the grid cell on commas and looped the
labels — but the per-option branch set `selected = false` on every non-match, so each label deselected what
the previous one selected. Only the last brand would have survived, and an operator would have quietly lost a
brand every time they edited a phone number. Invisible while every select in the app was single-valued.

**A method nobody calls was still load-bearing.** `findByCompanyId` was verified unused — and removing the
property it derives from **took down the entire application context**, because Spring Data builds derived
queries at startup. Repointed to `findByCompanies_Id` rather than deleted: "which suppliers represent this
brand" got *more* useful, not less.

**⚠ Keeping a column for safety only works if rows can still be written.** V47 stopped writing
`vender.company_id` but left it `NOT NULL` with no default, so *every* vendor insert failed with
`Field 'company_id' doesn't have a default value` — the new path and the legacy one alike. A frozen column has
to be **optional**, or it is not frozen; it is mandatory and unfilled. Fixed forward in V48, not by editing an
applied migration.

`FlywayMigrationTest` also caught a fifth: the backfill assumed the PK column was `id`, and it is `vender_id`.
That test runs every migration against an **empty** database precisely so the dev database's shape cannot be
assumed.

## Compatibility

`/addVender` still accepts the singular `companyId`. Four Cypress specs and any integration outside this repo
post it; widening an endpoint must not break its existing callers. `companyIds` wins when both arrive, and a
gate case covers the legacy shape so it cannot regress silently.

## Deliberately not done

`vender.company_id` is **left in place and populated**. Standard D5 forbids dropping on inference — count it
in every environment first — and production cannot be counted from here. The join table is authoritative; the
column is frozen history. Dropping it is separate, counted work.

## Known, pre-existing, not fixed

Company options load by AJAX when the vendor section opens, and `editRecord` can only tick options that
exist. Opening a record before that call returns silently selects nothing, and saving then writes that back.
The single-select had the same hole; multi-select only made it visible. Narrow, but the consequence is quiet
data loss. Fixing a load-order race in shared `editRecord` deserves its own slice.
