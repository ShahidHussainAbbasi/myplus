# Returns register parity — filters and Print all on both sides (task #24)

**Status:** DESIGN — gate written, implementation next.
**Design rationale:** `return-documents-design.md` **Part 5**. This doc carries what was found when the build
started, and the plan that follows from it. It does not restate Part 5.

Reported by the user: *"purchase returns and sale returns must have print all and filter options (main
parameters)"*. Ruling on Part 5 §5.7: **one screen, two modes.**

---

## 1. ⚠ "Sale returns have no Print all" is not a missing feature

`#returnsPrintAll` **already exists, is already wired, and is already mode-aware** — its handler reads
`window.returnsMode` and passes it to `printReturnDocuments(kind, noteNos)`, which has always supported both
kinds. It would print credit notes correctly today.

It is **inside `#returnsFilterBar`**, and `showReturns()` hides that whole bar on the credit side:

```js
if (window.returnsMode === 'debit') { $('#returnsFilterBar').show(); … }
else { $('#returnsFilterBar').hide(); }        // ← takes Print all with it
```

So the feature is built and unreachable on the screen that needs it.

**This is the tenth instance of the pattern** tallied in `SAAS-BUILD-STANDARDS.md`, and the second *this week*
where a working control was hidden by a container it happened to sit in — `.pos-more` did it to `#sellSerial`
and `#sellBonus`.

### The lesson, in one line

**Print all is not a property of the filter.** It acts on whatever is listed, in any mode, filtered or not.
Coupling it to the supplier picker was a layout accident that read as a missing feature for months.

So the fix is structural: the bar becomes **mode-configured controls** plus **mode-independent actions**, and
Print all lives in the second group where it always belonged.

## 2. What actually has to be built

| Piece | Where | Note |
|---|---|---|
| Print all reachable in both modes | template + `showReturns` | a layout fix, no new behaviour |
| Customer picker on the credit side | `RETURN_MODES` gains the party picker per mode | |
| **Customer filtering** | `getSaleReturns` | ⚠ the real work — see §3 |
| Date range, both modes | both endpoints | ⚠ must use `AppUtil.endOfDay` |
| Product filter, both modes | both endpoints | `ProductPicker`, the cached lean projection |

## 3. ⚠ Why the customer filter is different from every other filter here

`SaleReturn` has **no `customerId`**. Date and product are columns on the row; the customer is not on the
return at all. It is reached by walking `sellId → Sell → CustomerHistory → Customer`, which is exactly what
`getSaleReturns` already does to put a name in the register — batched, two lookups for the whole page.

So the filters split by where the data lives:

| Filter | Applied | Why |
|---|---|---|
| Date, product | **in SQL** | plain columns on `SaleReturn` / `PurchaseReturn` |
| **Customer** | **in memory, after the enrichment the register already performs** | the value does not exist on the row being queried |

**Filter on the customer's ID, not their name.** The enrichment currently keeps only a
`sellId → name` map; names collide, and a register that quietly included another customer's returns because
two people share a name is worse than no filter. The map gains the id alongside the name.

⚠ **This does not scale, and the code says so.** In-memory narrowing after a full scoped read is honest at
today's volumes and matches how `SaleReportFilter` already narrows the sale report. A join through `Sell` is
the right answer once a tenant's return history is large. Whoever hits that wall should find the reason
written down rather than have to rediscover it.

## 4. ⚠ Two things carried in from the slices that shipped today

**`AppUtil.endOfDay` on every new bound.** A date range that parses its own bounds reproduces the
`report-date-bounds` defects exactly: a same-day filter returning nothing, because the picker sends midnight
and `00:00:00..00:00:00` matches only a return recorded at exactly midnight.

**Print all now emits NAMED documents.** Until this morning every credit note printed with an empty customer
block (flat `customerName` where `buildContext` reads `inv.customer.name`). A "print all" shipped before that
fix would have produced a stack of anonymous sheets — the failure mode a bulk action makes worst, because
nobody proofreads forty pages. Case 10 guards it.

## 5. Cypress cases

Spec: `cypress/e2e/business/returns-parity.cy.js`. Part 5 §5.6 lists 1–8; 9 and 10 are added from §1 and §4.

1. Both registers show a filter bar and a Print all button.
2. The party filter narrows the list; an impossible value returns nothing rather than everything.
3. The party control is a CUSTOMER picker on credit notes and a SUPPLIER picker on debit notes.
4. A date range covering today returns today's returns — the `endOfDay` regression, on this screen.
5. Print all fetches every listed note and combines them into ONE job, both sides.
6. Print all with nothing listed refuses rather than opening an empty job.
7. Filters survive a mode switch without leaking — a supplier filter still applied after switching to credit
   notes would silently show an empty register.
8. Cross-tenant: another tenant's notes never appear, whatever the filter.
9. **⭐ Print all is VISIBLE on the credit side** — §1's defect, asserted from the SCREEN. Calling the handler
   would pass today and prove nothing.
10. **⭐ Every sheet in a combined credit-note job carries its customer's name** — §4's regression guard,
    rendered through the production shaping.

## 6. What is deliberately NOT in this slice

- **Reason as a filter.** Free text today; useful only once it is a chosen value.
- **A join for the customer filter.** §3 — the in-memory narrowing is the documented, deliberate choice.
- **Download all as PDF.** Blocked on #25, whose loader half is now proven working
  (`return-documents-design.md` §6.3b) but whose file-delivery half is still unverified.
