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
