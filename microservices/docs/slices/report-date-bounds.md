# Report date bounds — three defects in how a date range is interpreted

**Status:** FIXED 2026-09-01, gated by `bonus-schemes-p3.cy.js` case 14.
**Not related to any feature.** Found while walking the #17 P3 manual test and setting a filter.

These are long-standing and affect **every** date-range read, including the dashboard's headline numbers.

---

## 1. A same-day range returned nothing

The date pickers send a date-only selection as **midnight**. So "1 Sep to 1 Sep" arrived as:

```
sd = 01-09-2026 00:00:00
ed = 01-09-2026 00:00:00
```

`findSellByDates(sd, ed)` then matched only a sale rung at exactly `00:00:00.000` — in practice, nothing. A
shop picking today for both ends saw an empty report with a full day's takings behind it, and reasonably
concluded their sales were missing.

**Fix:** `AppUtil.endOfDay(...)` rolls a midnight value to `23:59:59.999999999`, so the bound means what the
operator selected — the whole day. A caller that genuinely supplied a time keeps it, so "up to 09:00" still
means 09:00.

---

## 2. "Current month" excluded the morning

```java
// was
public LocalDateTime firstDateTimeOfMonth() {
    return LocalDateTime.now().withDayOfMonth(1);   // keeps the CURRENT TIME OF DAY
}
```

On 1 September at 09:15 the range began at **1 Sep 09:15**. Every sale rung earlier that morning fell outside
it. On the first of the month a shop could open the report and see nothing at all.

## 3. …and the last day's evening

`lastDateTimeOfMonth()` had the mirror defect — it also kept the current time of day, so on the last day of the
month every sale rung after "now" fell outside the month it belongs to.

**Fix:** both now use true boundaries — first day at `00:00:00`, last day at `23:59:59.999999999`.

---

## ⚠ Why this matters more than a report

Those two helpers also feed **`getBusinessDashboardStats`**:

| Reader | Was |
|---|---|
| Monthly revenue tile | under-reported |
| Monthly sales count | under-reported |
| Six-month trend chart | under-reported |

All three silently, for the same reason. **A number that is slightly low still looks like a number** — nobody
would notice, and nobody did. It took someone setting a filter and reading the result.

---

## The rule

**A date-only bound is a DAY, not an instant.** A start date means that day's first moment; an end date means
its last. Anywhere a range is built from a picker, say so explicitly rather than letting `LocalDateTime.now()`
leak the current clock time into a boundary.

Detector for the same class of bug elsewhere:

```bash
grep -rn "now().withDayOfMonth\|now().withDayOfYear" microservices/ --include=*.java
```

### Detector results (run 2026-09-01)

Two further hits, both checked:

| Hit | Verdict |
|---|---|
| `TaxBreakdownService:37` — `LocalDate.now().withDayOfMonth(1)` | **safe** — a `LocalDate` has no time component to leak |
| `AppUtil.dateTimeByDay(int)` | **same defect, no callers.** Left in place with a warning javadoc rather than changed, since altering unused code carries risk for no benefit — but anyone reaching for it is now told |


---

## 4. A fourth defect, same area: the period itself

Found when "Current month" was already selected and View report sent a request with no dates in it.

### 4a. An absent period THREW

```java
int CURRENT_MONTH = 0;
if (dto.getRp() == CURRENT_MONTH) {     // Integer == int  ->  UNBOXES
```

`rp` is an `Integer`. A request that carried no `rp` threw `NullPointerException`, which the catch turned into
a bare "could not load the report" with nothing to act on. That request is not hypothetical: `.val()` on a
select that has not rendered yields `undefined`, jQuery omits the field, and the report can be opened from a
nav entry, a deep link or a back-button restore before the screen is built.

### 4b. A period with no range matched NO branch

`rp=4` (custom range) with no dates fell through every branch, left `objs` null, and returned **NOT_FOUND** —
"you have no sales" — to a shop full of them.

**That is the worse of the two.** An error at least says something failed; an empty report says the data is
gone. A report that answers a malformed question with an empty result teaches an operator to distrust their
own records.

### The fix, on both sides

| Side | Change |
|---|---|
| Server | `Integer rp` read once, null-safe; absent period + no range = current month; a final `objs == null` fallback so no request can silently produce "no sales" |
| Client | `loadSR()` defaults `rp` to `'0'` when the control has not answered |

Both sides default the same way **deliberately** — neither should depend on the other remembering.

Gated by `cypress/e2e/business/sale-report-period.cy.js`, including an end-to-end case that clicks View
report, because every other case posts a body the test itself built — and a hand-built request cannot detect a
screen that sends the wrong one. That is precisely how this survived: the server was fine for the requests
anyone thought to make.
