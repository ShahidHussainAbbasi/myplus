# EX-2b — Tag an expense to what it was for (school, vehicle, land)

**Status:** GREEN 2026-10-02 15:55, STAGED. Deployed: expense-service (V2 applied live), education-service, agriculture-service (tag endpoints), monolith (built from a clean worktree = HEAD + EX-2b only, so another session's in-progress DR-2 screens were NOT shipped). Unit: expense 16/16 (Flyway V1+V2 on empty MySQL, Skipped 0), education 223, agriculture 2. Gate 4/4; regression EX-1 + EX-2a 24/24. Live DB: two tagged lines with the modules' labels; the four refused requests stored nothing. Branch `feature/expense-management`.
Programme: [`../expense-management-design.md`](../expense-management-design.md) §4c, §5 (DimensionProvider SPI).

## 1. Document
An expense says what it was for: the bus's fuel, the branch's electricity, the wheat field's fertiliser. Without
it a school cannot tell what each bus costs and a farm cannot cost a field. EX-2a put Expenses on every dashboard;
this slice lets a line carry ONE tag from the module it belongs to.

Out of scope, recorded: **welfare has nothing to tag to** (no campaign/fund entity — only donations and donors);
business STORE tagging rides `storeId` later; crop is a free-text field on agriculture rows, not an entity.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | A tag is a reporting dimension, never an account: it never changes the journal. Snapshot the label at save, so renaming the bus never rewrites history |
| Tenancy / anti-IDOR | The server CONFIRMS every tag through the owning module, with the caller's own identity and visibility (branch grants included). A foreign or out-of-branch id is refused, never stored |
| Boundaries | **The module owns WHO** (the notification-audience rule): education decides which schools/vehicles the caller may see, agriculture which lands. expense-service owns only the tag reference |
| Patterns | **SPI / Ports & Adapters**: one contract (`ExpenseTagClient`, `GET /expense-tags` → `[{type,id,label}]`) each module implements; expense-service holds a **Strategy registry** type → source (`SCHOOL`,`VEHICLE` → education; `LAND` → agriculture). Adding a vertical = one endpoint + one registry line |
| DRY | Branch visibility is called through education's `RequestUtil`, not re-derived; the Expenses screen gains one optional field, driven by a fragment parameter rather than a per-dashboard copy |
| Performance | Tags are read once when the form opens and once per save — never on a list or a hot path |
| Testing | Unit: registry + snapshot; Cypress per domain: tag a vehicle (school) and a land (farm), refuse a foreign id and a type from the wrong module |

## 2. Design
- contracts: `ExpenseTagView{type,id,label}`, `ExpenseTagClient` (`@GetExchange("/expense-tags")`).
- education-service: `ExpenseTagController` `GET /expense-tags` → visible schools (`SCHOOL`) + visible vehicles
  (`VEHICLE`), label "Name (number)" for a vehicle.
- agriculture-service: `ExpenseTagController` `GET /expense-tags` → lands (`LAND`), scoped as `getUserLand`.
- expense-service: V2 adds `tag_type VARCHAR(16)`, `tag_id BIGINT`, `tag_label VARCHAR(160)` to
  `expense_voucher_line` + index `(tag_type, tag_id)`; `ExpenseTagService` (registry, `options(source)`,
  `confirm(type,id)` → label or refusal); `GET /api/expense/tags?source=education|agriculture`;
  `LineRequest.tagType/tagId`.
- monolith: `/expense/tags` proxy; fragment `expenseSection(tagSource)`; `expense.js` shows a "For" select when
  the section declares a source; the list shows the tag beside the category.

## 4. Gate (written first) — `cypress/e2e/expense/ex-2b-tags.cy.js`
1. School: the "For" list offers the business's schools and vehicles; record fuel tagged to a vehicle → the line
   carries that vehicle's label; the journal is unchanged by the tag (same accounts as untagged).
2. Farm: tag to a land → label stored.
3. Refusals (API): a land id from the farm sent by the school → refused; type `VEHICLE` sent by the farm → refused
   (no such source data); unknown type → refused.
4. Shop: no "For" field (no source declared).
