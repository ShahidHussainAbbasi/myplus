# AN-1 — Wire analytics-service end to end (finance trend)

**Status:** DONE 2026-10-09: gate 5/5 (seen red first), unit tests 9 new (analytics 6/6, finance 3), Test Book case 9-6. Programme: [`../expense-management-design.md`](../expense-management-design.md) E12 ("wiring
analytics is its own programme item"). Follows EX-8c, closed as superseded by EX-8a on 2026-10-09.

## 1. Document
analytics-service has been in the codebase since the platform split, but nothing ever reached it. Nothing writes its
metrics, no screen reads it, and it is not in the default stack. The Profit & Loss screen shows one period. An owner
cannot see the last twelve months side by side without running the report twelve times.

AN-1 connects analytics end to end for the finance figures:

```
P&L screen ──/gl/pnlTrend──▶ monolith ──/api/analytics/financial/monthly──▶ analytics-service
                                                                               │  1. ask finance for the months
                                                                               ▼
                                         finance-service  GET /api/finance/gl/pnl/monthly   (the P&L, month by month)
                                                                               │  2. upsert finance.revenue / finance.expenses
                                                                               ▼
                                                              aggregated_metrics (the caller's org, MONTHLY)
                                                                               │  3. serve the stored months
                                                                               ▼
                                                              12-month trend under the P&L
```

The sales metrics (`sales.revenue`, `sales.count`) are **AN-2**. They need a producer in business-service, which is a
separate decision. AN-1 does not touch them, apart from the column type below.

## 1b. Standards
| Dimension | Rule |
|---|---|
| One source of truth | finance computes each month with the **same** `profitAndLoss(from, to)` the P&L screen uses: revenue = INCOME credit − debit, expense = EXPENSE debit − credit. Analytics never re-derives a number from journals |
| Fresh on read | every read asks finance for the requested months and upserts them. A month that changes (a back-dated expense, a void) is right the next time it is read. No schedule and no event are involved, so nothing can be missed |
| Stale, said so | finance down → analytics serves the months it stored before, marked `stale: true` with each month's `computedAt`. It never shows invented zeros. No months stored and finance down → 503 with the reason |
| Who may read | the **same** rule as finance's statements (`ROLE_OWNER`, `ADMIN_PRIVILEGE`, `SUPER_PRIVILEGE`), checked by analytics itself. Analytics serves stored months when finance is down, so finance cannot be the only gate. finance refuses (403) → analytics refuses (403) and serves nothing |
| Tenant | rows are stamped with the caller's org. The finance metrics read **only** the caller's org: no legacy `organization_id IS NULL` fallback, since a NULL row would be added into a tenant's total |
| Money | `aggregated_metrics.value` becomes `DECIMAL(19,2)` (was `double`), so analytics totals equal the P&L to the cent |
| One row per month | unique `(organization_id, metric_name, period_type, period_start)`. The write is `INSERT … ON DUPLICATE KEY UPDATE`, so two readers at once cannot make a duplicate |
| Range | at most 24 months a call; `from` after `to` refused in words |
| The stack | analytics-service moves from the `full` profile into the default set. It is 768 MB, and about 6.8 GB is free here |
| Failure isolation | the trend is its own call under the P&L. Analytics down → the P&L still shows, with one line saying the trend is unavailable |

## 1c. RULE 0 trace
| What | Count | Finding |
|---|---|---|
| Readers of `aggregated_metrics` | 7 | `FinancialAnalyticsService` 3 (summary ×2 names, revenue-by-period: **want** the new rows, now strict-org); `SalesAnalyticsService` 3 (`sales.*`: unaffected by name, only the value type changes); `MetricAggregationService.getMetrics` 1 (generic, by name: unaffected) |
| Writers | 1 (`saveMetric`), **0 callers** | AN-1's upsert is the first producer |
| Readers of `getValue()` (the type change) | 9 call sites in 2 services | all adapted to `BigDecimal`; `MetricDTO.value` stays a `Double` on the wire (sales endpoints unchanged in shape) |
| Producers of `finance.revenue` / `finance.expenses` | 0 | the reason the summary always said 0 |
| Screens reading `/api/analytics` | 0 | the trend panel is the first |
| Gateway route | 1 (`/api/analytics/**`, no StripPrefix; the controllers carry the full path) | reachable once the service runs |
| The service under `validate` | never started here: `myplusdb_analytics` exists with **no tables** | the first start runs V1–V3; the start itself is part of the gate |
| finance P&L callers | 2 (`/gl/pnl` screen proxy, the EX-8a reconciliation in tests) | unchanged; the monthly endpoint calls the same method once per month |
| Identity on the hop | `GatewayIdentityForwarding.interceptor()` (X-User-*, X-Org-Id, privileges, X-Internal-Secret, zone) | finance scopes and authorises as the original caller |

## 4. Gate: `cypress/e2e/finance/an-1-analytics-trend.cy.js` (school tenant)
1. ⭐ Analytics' month = the P&L: for each of the last 12 months, `/api/analytics/financial/monthly` revenue and expenses
   equal `/api/finance/gl/pnl?from=<1st>&to=<last>` totals to the cent; the summary over the range equals their sum.
2. ⭐ Fresh on read: record an expense today; the current month's expense in analytics goes up by exactly that amount,
   and a void puts it back.
3. ⭐ Who may read: the school's plain user (no statements privilege) is refused by analytics (403), as finance refuses
   them.
4. ⭐ On screen: the P&L shows a "Last 12 months" table whose current month matches the P&L's figures.
5. Range: more than 24 months, or `from` after `to`, refused in words.

## 5. As built
- **Seen red first:** with analytics running (V1–V3) but finance not yet carrying `/gl/pnl/monthly`, cases 1, 2 and 4 failed
  on `503 Service Unavailable` (finance answered 404 → "unavailable", nothing stored → 503: the stale path, not an
  invented zero). Cases 3 and 5 passed already: analytics' own statements rule and range checks.
- **Green:** 5/5 after deploying finance and the monolith.
- **The store, verified in the DB after the gate:** school org 7 holds 12 `finance.revenue` and 12 `finance.expenses`
  rows, 12 distinct months each, so there were no duplicates across three refreshing runs. V3 ran under `ddl-auto=validate`
  (`value` is `decimal(19,2)`, `uk_aggregated_metrics_period` is present) and the service started with 0 restarts.
- **On screen (Test Book 9-6):** the lifecycle business's P&L total for this month (7,280.00) equals the trend's
  October row; the plain user is refused (403).
- **Regression:** `ex-2c-books-everywhere` 6/6 and `business/finance-report-dialogs` 9/9 (both open the P&L screen).
- **Not covered here (AN-2):** the sales metrics. `SalesAnalyticsService` reads `sales.revenue`/`sales.count`, which still
  have no producer; only their value type changed (DECIMAL; the wire stays a double).
- **Changed files:**
  - finance: `GlService.monthlyProfitAndLoss`, `GlController /pnl/monthly`.
  - analytics: V3, `AggregatedMetric.value`, `AggregatedMetricRepository.findOrgMetric/upsert`,
    `FinanceMetricsClient`, `ClientsConfig`, `FinancialAnalyticsService`, `FinancialController`, `FinancialTrendDTO`,
    `SalesAnalyticsService` (value type).
  - monolith: `AnalyticsRestClient`, `GlController /gl/pnlTrend`, `finance-reports.js finAppendPnlTrend`.
  - compose: analytics is in the default set.

## 6. AN-2 — closed as superseded (owner's ruling 2026-10-09)
AN-2 was meant to feed `sales.revenue`/`sales.count` into analytics. The trace found that the business dashboard
**already** shows the sales trend from business-service (`getDashboardChartData`: 6 months of revenue and number of
sales, and daily revenue this month, by SQL `GROUP BY`, scoped to the business). Analytics' 3 sales endpoints
(`/api/analytics/sales/trend`, `/daily`, `/summary`) had **0 readers** and read a metric nobody writes. A second copy of
the same figures would be one more thing to keep in step.

Removed: `SalesAnalyticsController`, `SalesAnalyticsService`, `SalesAnalyticsDTO`. 3 files; 0 other references (the
`ReportDefinition.Type.SALES` enum value is a stored report type, unrelated, kept). Afterwards
`/api/analytics/sales/trend` answers 404, analytics' unit tests pass 6/6, and the AN-1 gate passes 5/5.
