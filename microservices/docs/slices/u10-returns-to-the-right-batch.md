# U10 — a returned tablet goes back to the batch it came from

**Status: DONE + GREEN 2026-08-30 — sell-loose-return.cy.js 16/16, including four that read PER-BATCH on-hand.** Branch: `feature/pack-loose-selling`.
Closes the open item U6 §5 deferred.

---

## 1. Review — and U6's reasoning for deferring this was wrong

U6 declined batch-level returns on the grounds that *"`SaleReturn` has no batch column"*. **The batch never
needed to be on `SaleReturn`.**

| Verified | Where |
|---|---|
| A sale records the batches it took, per line | `SellBatch` (sellId, productId, batchNo, expiryDate, quantity) |
| The reservation records them too, per pick | `ReservationPick` |
| `StockImportLine` already carries `batchNo` + `expiryDate` | `commerce-contracts` |
| ⭐ **`ReservationService.returnPicks` ALREADY restores to the sale's original batches** | `inventory-service:244` |

`returnPicks` restores each returned unit to its **original batch**, capped per pick by
`quantity − returnedQuantity` so repeated partial returns can never over-restore, keeping the real expiry so
FEFO stays correct — and quarantines instead when the caller asks. It is more careful than the design I was
about to write.

**So this was never a missing capability. It was a missing CALL.**

## 2. ⚠ The gap: two return paths, one of them batch-blind

```
  the dedicated return  ──▶ inventoryClient.returnStock(reservationId, …)
                             └─ returnPicks: original batch, real expiry, capped per pick   ✅

  an EDIT of the invoice ──▶ importStock(bare line)
                             └─ a FRESH StockEntry: no lot, no expiry                       ❌
```

**And an edit is how a loose return happens.** U6 established that: the cashier reduces the line and the
difference becomes a credit note. So every returned tablet re-entered stock as an untraceable, undated entry.

The **quantity was right** — which is why nothing looked wrong. What was lost:

* **FEFO order.** The returned units sit in a batch with no expiry, so they are not sold ahead of the
  near-dated batch they actually came from. The shop's oldest stock ages while returned stock sells.
* **Lot traceability.** A recall on batch `X` cannot find units that came back from a sale of batch `X`.

*The third time in this programme that a quantity was correct and its context was not.*

## 3. The change

An edit's return now goes through the **same reservation** the sale used, so both paths are identical:

```java
if (!returnLines.isEmpty() && ch.getReservationId() != null) {
    inventoryClient.returnStock(ch.getReservationId(), new StockReturnRequest(byBatch));
    returnLines.clear();          // do not also import a second, batchless copy
}
```

**No new table, no migration, no allocation logic here.** Inventory already caps per pick, so an edit that
returns more than this reservation took is resolved there — by the code that owns the batches — rather than
guessed at by the caller.

### 3.1 The fallback is deliberate

If the batch-aware return throws, the flat import still runs. *A returned unit in the wrong batch is a
traceability problem; a returned unit in no batch at all is a missing one.* Losing the stock would be the
worse failure, so the fallback stays and logs loudly.

A sale with no `reservationId` — legacy, pre-saga — takes the flat path exactly as before.

## 4. Gate — to add to `sell-loose-return.cy.js`

1. ⭐ **a loose return goes back to the batch it came from** — sell 5 tablets from batch `A`, return 3, and
   batch `A`'s on-hand rises by 0.3. Not a new batch.
2. ⭐ **the expiry survives** — the returned units carry batch `A`'s expiry date, so FEFO still sells them
   first.
3. **a pack return behaves the same** — this is not loose-specific.
4. **repeated partial returns never over-restore** — return 2, then 2 more, and batch `A` never exceeds what
   the sale took from it. *Inventory's cap, asserted through the edit path.*
5. **a legacy sale with no reservation still restocks** — the flat fallback, quantity correct.
6. **the total on-hand is unchanged by any of this** — the regression: batch placement moved, quantity did
   not.

## 5. Performance

No new query in business-service — `reservationId` is already on the invoice it just loaded. Inventory does
the same work it already does for the dedicated return path.

## 6. Security

Unchanged: the return is scoped by the reservation, which belongs to the invoice, which is org-scoped.

### 4.1 Why the gate needed a per-BATCH read

Total on-hand **cannot see this defect**. A returned unit lands in *a* batch either way, so the total is right
whether the lot is right or not — which is exactly why nothing noticed for the whole programme.

The cases read `/getStockByBatch?batchNo=&productId=`, so they distinguish *back where it came from* from
*in a fresh, undated entry*. One case still asserts the total, as the regression: **U10 moved where stock
lands, not how much.**
