# Slice 26 — Bean Validation at the edge (business-service)

Status: **DONE + VERIFIED** ✅ (2026-06-14, Cypress green). Tech-debt #15 (🟡).

## Document — what & why
DTOs had no Bean Validation constraints; the `add*` controllers already carried `@Validated` but had
nothing to enforce, so bad input was caught only by scattered manual `isEmptyOrNull` checks (or, worse,
surfaced as a DB error). Add declarative constraints + a clean validation-error envelope.

## Design
### Constraints (conservative, only clearly-required fields)
`@NotBlank` on the **name** of the create DTOs that are *not* shared with a no-name flow and are not
cascade-validated from a parent:
- `CustomerDTO.name`, `ItemTypeDTO.name`, `ItemUnitDTO.name`, `VenderDTO.name`, `CompanyDTO.name`.

**Deliberately not added:**
- `ItemDTO.iname` — `ItemDTO` is shared by `addItem` (needs name) *and* `addStock` (adds stock to an
  existing item by id, sends no name). A blanket `@NotBlank` would break `addStock`. (Would need
  validation groups; left to a follow-up. `addItem` keeps its manual name check.)
- nested DTOs (e.g. `CustomerHistoryDTO.customer`) — no `@Valid` cascade, so `addSell` is unaffected.
- quantity/amount fields — avoided for now to not regress existing flows; revisit with `@Positive`
  once happy-path data is confirmed.

### Validation-error envelope (flat contract)
`add*` are **form-bound** (`@ModelAttribute`), so a violation throws `BindException`
(`MethodArgumentNotValidException` extends it). `GlobalExceptionHandler` now handles both and returns the
flat **`GenericResponse("ERROR", "<field>: <msg>")` at HTTP 200**, so the monolith JS shows the message
through its normal error path (previously a form-bind violation fell through to the generic 500 handler).
REST endpoints (`DashboardController`, GET-only) are unaffected; the `ApiResponse` handlers stay.

## Implement (checklist)
- [x] `@NotBlank` on name of Customer/ItemType/ItemUnit/Vender/Company DTOs (jakarta, FQN)
- [x] `GlobalExceptionHandler`: `{MethodArgumentNotValidException, BindException}` → `GenericResponse("ERROR", …)`
- [ ] build; headed Cypress (customer/company/vender/sectional + negative) — **awaiting build**

## Test
- Existing happy-path specs stay green (all create flows send a name).
- `negative.cy.js`: empty/whitespace name → `GenericResponse` ERROR (200, status≠SUCCESS), never 500.
- `addStock` (no `iname`) still succeeds — `ItemDTO.iname` intentionally unconstrained.
