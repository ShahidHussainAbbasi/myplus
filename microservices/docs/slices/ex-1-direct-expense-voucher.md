# EX-1 — `expense-service` + direct expense voucher (business dashboard)

**Status:** GREEN 2026-10-02 09:50. Deployed: expense-service (V1 applied; `myplusdb_expense` auto-created on the existing
volume — verified), api-gateway (route live). Monolith already carried the final EX-1 screen (deployed 09:22 from the
shared tree; the four screen files are byte-identical — checked). Gates: unit 8/8 + ExpenseFlywayMigrationTest 2/2
(Skipped 0), API 7/7, UI 4/4, EX-0a regression 10/10. Ledger cross-checked in finance's DB: EXP-000001…000006 each a
balanced journal on the right accounts, EXP-000003 reversed by an exact mirror.

## 1. Document
A shop records money already paid — rent, power, fuel — paid from **cash or bank**, and it lands in the books
(QuickBooks "Expense", Xero "Spend Money", Tally "Payment voucher"). First slice a tenant can use.

## 1b. Standards
| Dimension | Rule |
|---|---|
| Business | A posted voucher is never edited/deleted; void = reversing journal (EX-0b mirror). Number `EXP-000001` per org, allocated LATE via `common-docnum` |
| Tenancy | org from JWT; scoped reads; foreign id → 404; USER sees own vouchers, OWNER/ADMIN all (data-visibility rule) |
| Live modules | capability `expenseManagement` (opt-in, OFF): writes refused `{success:false}` when off; menu hidden |
| Boundaries | new service `expense-service` :8097, DB `myplusdb_expense`; finance stays the only journal writer |
| Patterns | Transactional outbox (`expense_outbox`, payload = whole `PostingEventRequest` JSON → no silent-drop trap) · idempotent consumer (finance `processed_event`, key `EXP-{org}-{id}-POST/VOID`) · command endpoints · `@Version` · Idempotency-Key + UNIQUE (DUP-1/BLK-5) · `AuditEmitter` (education's pattern) |
| DRY | `common-docnum`, `common-outbox`, `common-audit`, `common-web`, shared `FinanceClient`; UI as `/js/common/expense.js` + `fragments/expense.html` (EX-2 reuses on 3 dashboards) |
| Testing | unit: state machine, credit-account strategy, line → posting mapping, payload round-trip; Flyway on empty MySQL (Skipped 0); headed Cypress below |

## 2. Design (decisions from the review)
- **Scaffold = party-service** (pom, Application, SecurityConfig + HeaderAuthFilter, yml, bootstrap, Dockerfile).
  Register in ALL of: parent `<module>`, gateway route `/api/expense/**` (no StripPrefix), docker-compose block
  (default set, no profile, like party), `start-all.ps1` AND `stop-all.ps1` catalogs, `deploy.ps1` `$schemaOf`,
  `init-db.sql` (CREATE DATABASE + GRANT). Own `@LoadBalanced RestClient.Builder` bean (welfare pattern);
  `FinanceClient` + `AuditClient` beans with timeouts + `GatewayIdentityForwarding.interceptor()`.
- **Contract:** add `lines` (List<PostingLine{accountCode,debit,credit,lineMemo}>) to commerce-contracts
  `PostingEventRequest`, javadoc'd "never from a field-by-field outbox".
- **Tables (V1):** `expense_category` (UNIQUE org+code; seeded lazily with 8 defaults → 6000…6900),
  `expense_voucher` (status DRAFT|POSTED|VOIDED, posting_status NONE|PENDING|POSTED_GL|FAILED, total stamped,
  idempotency_key UNIQUE(org,key), version), `expense_voucher_line`, `expense_outbox` (voucher_id, event_key
  UNIQUE, payload JSON), `org_document_seq` (repo implements `DocumentCounterStore`), `audit_outbox`
  (columns EXACTLY `AbstractAuditOutbox`: action(32), entity_type(32), entity_ref(64), amount(19,2), details(500),
  reason, before_value, after_value, actor_org_id, actor_type, actor_email, event_key(64), occurred_at, status,
  attempts NOT NULL, last_error(500), organization_id, user_id, created_at, updated_at).
- **Posting status is stamped at write:** the outbox channel's `save()` sets the voucher's posting_status when the
  relay marks the row POSTED/FAILED.
- **Void:** only when posting_status = POSTED_GL ("wait until it is in the books"); a FAILED voucher voids with no
  reversal event. Reason required.
- **Authority (EX-1 maps to existing tiers; dedicated EXPENSE_* privileges come with EX-6):** any tenant member
  records + posts (money already paid); void + categories = ROLE_OWNER or ADMIN_PRIVILEGE.
- **Capability check:** caps from token; unresolved (null) → refuse writes (money guard fails CLOSED; not
  `capabilityAllowed`, which is permissive by design).
- **API** `/api/expense`: GET/POST/PATCH `categories`; GET `vouchers?from&to&status&page&size`; GET
  `vouchers/{id}`; POST `vouchers` (Idempotency-Key header, `post` flag); POST `vouchers/{id}/post`;
  POST `vouchers/{id}/void`; DELETE `vouchers/{id}` (DRAFT only).
- **Monolith:** `ExpenseRestClient` (GatewayClient, `/api/expense`), `ExpenseController` proxies with JSON bodies
  (no repeated-param collapse, URI encoding handled), errors via `GatewayClient.errorMap`. Menu: **Till → Expenses**
  `[data-capability="expenseManagement"]`; section list + New expense form (date, category, amount, paid from,
  payee, note; one line in EX-1 UI) + status chip Posting…/In the books/Failed/Void + void via `uiPromptConfirm`.
  i18n `ui.*`/`ui.js.*` in 6 languages.

## 4. Cypress gate (write FIRST) — `cypress/e2e/expense/ex-1-direct-voucher.cy.js`
owner.business; `before` switches the capability ON, `after` resets it (leave no server state).
1. OFF → no Till→Expenses item; API create refused `success:false`.
2. ON → owner records Rent 2,500 cash via the UI → EXP- number; chip reaches "In the books" (bounded poll);
   **trial balance: 6000 Dr +2,500, 1000 Cr +2,500, still balanced** (the money assertion).
3. Paid from bank → 1010 credited, not 1000.
4. Void with reason → both accounts back to the before-values; voucher kept, VOID.
5. Same Idempotency-Key twice → one voucher.
6. Category mapped to 1200 → refused at save with the boundary message.
7. user.business records + sees only own; void refused. owner.pesticide GET the id → not found.

Manual cases → test guide artifact WyTiq6t4bNH4UBG3CJ9337 §EX-1.

## 3. Implement (done)

- [x] gate written first: `cypress/e2e/expense/ex-1-direct-voucher-api.cy.js` (7, gateway-direct, trial balance) and
      `ex-1-direct-voucher-ui.cy.js` (4, real UI)
- [x] commerce-contracts: `PostingEventRequest.lines` + `PostingLine`; `FinanceClient.ensureDefaultAccounts` + `GlAccountView`
- [x] expense-service: scaffold, V1 (6 tables, InnoDB, types = entities), entities, repos (scoped; targeted
      `stampPosting`), `ExpenseAccess` (capability fails CLOSED, tiers), `PaidFrom` strategy, `VoucherPostings` (pure),
      `ExpenseOutboxService` (payload JSON, stamps posting status on finance's answer), `ExpenseCategoryService`
      (lazy defaults, boundary check at save), `ExpenseVoucherService` (idempotency, late number, void), controller,
      audit via `AuditEmitter`
- [x] registration: parent pom, gateway route, docker-compose (default set), start-all + stop-all, deploy.ps1
      `$schemaOf`, init-db.sql
- [x] monolith: `ExpenseRestClient`, `/expense/**` proxy (service status + message passed through),
      `fragments/expense.html`, `/js/common/expense.js`, Till → Expenses menu item, 23 i18n keys × 6 languages
      (reuses existing `ui.newExpense`)
- [x] `ExpenseFlywayMigrationTest` RUN with Skipped 0 (skipped 2/2 — Docker down)
- [x] deploy expense-service + api-gateway; verify the app user can create `myplusdb_expense` on the EXISTING volume
- [x] API gate 7/7; then monolith deploy (after DR-1 commits) and UI gate 4/4

Findings during the build (RULE 0):
- `list()` of categories must NOT run in a read-only transaction: the REPEATABLE READ snapshot taken at the count
  would hide the rows the seed just committed — a tenant's first list would be empty. Fixed before any run.
- A `@Modifying` query is not transactional by default; the relay calls it outside a request. Annotated.
- `ui.newExpense` already existed (agriculture) — reused rather than duplicated.
