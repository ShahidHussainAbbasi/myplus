package com.myplus.inventory.service;

import com.myplus.common.security.time.TenantClock;

import com.myplus.commerce.contracts.dto.*;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.inventory.entity.Reservation;
import com.myplus.inventory.entity.ReservationPick;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.StockLevel;
import com.myplus.inventory.repository.ReservationRepository;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stock reservation saga participant (slice 33, Phase 6a). reserve → FEFO hold (no decrement);
 * confirm → decrement stock; release → return the hold. Idempotent on the caller's idempotency key
 * (reserve) and on reservationId (confirm/release). org/user are passed in (the controller reads CurrentUser),
 * so the logic is unit-testable without a web/security context.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockLevelRepository stockLevelRepository;
    /** OMS O5a — how long this tenant's holds live. */
    private final ReservationPolicy reservationPolicy;

    /**
     * EXP-1 — whether this tenant keeps expiry dates.
     *
     * <p>⚠ This is the ALLOCATOR, so the flag decides what a sale may actually take off the shelf, not merely
     * what a screen says. With expiry tracking ON, a dated batch past today is excluded and a shop dispensing
     * medicines cannot sell it (G1, and {@code Shape.PHARMACY} now floors the capability so that cannot be
     * switched off). With it OFF — a mobile shop, a general counter — an old date on a row is not a reason to
     * refuse to sell perfectly good stock.
     */
    private final com.myplus.common.settings.CapabilityService capabilityService;

    /** U0: absent means zero, exactly. */
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    /*
     * U0 — the epsilon is GONE, deliberately.
     *
     * It existed because float subtraction never lands on zero, so a loop had to stop "close enough". With
     * exact decimals every comparison is true, and keeping a tolerance would now HIDE a real shortfall of up
     * to one epsilon rather than absorb a rounding artefact. A tolerance that no longer has anything to
     * tolerate is a silent allowance for being wrong.
     */

    /**
     * O7 D1c — release a hold addressed by the CALLER'S OWN KEY rather than our reservation id.
     *
     * <p>An order hold is identified by the order ({@code SO-42-HOLD}), which is the only handle the caller
     * has when it later rejects, cancels or dispatches. The alternative — returning our reservation id and
     * expecting the caller to store it — would put a column on their table to hold a foreign key into ours,
     * and leave the stock stranded whenever that write failed.
     *
     * <p><b>Silent when there is nothing to release.</b> The hold may already have gone to the expiry sweeper,
     * which is what the sweeper is for; a caller compensating a failure should not have to tell "already
     * gone" from "never existed", because both mean the stock is free.
     *
     * <p>Scoped by tenant: the key arrives over the wire, so another tenant's hold is simply not found.
     */
    @Transactional
    public StockReservationResponse releaseByKey(String idempotencyKey, Long orgId, Long userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        return reservationRepository.findByIdempotencyKeyScoped(idempotencyKey, orgId, userId)
                .map(r -> release(r.getReservationId(), orgId, userId))
                .orElse(null);
    }

    /** Trim a whole-number quantity to "7" instead of "7.0" for user-facing messages. */
    /** "7" not "7.0000" — the message is read by a cashier, so trailing zeros are noise. */
    private static String fmtQty(BigDecimal q) {
        if (q == null) return "0";
        BigDecimal t = q.stripTrailingZeros();
        return t.scale() <= 0 ? t.toBigInteger().toString() : t.toPlainString();
    }

    @Transactional
    public StockReservationResponse reserve(StockReservationRequest req, Long orgId, Long userId) {
        /*
         * Idempotency: a retried reserve with the same key returns the existing hold, never double-holds.
         *
         * A RELEASED hold is deliberately NOT returned, and that is a correctness fix, not a nicety. A
         * released hold is spent — it holds no stock — so handing it back to a caller asking to reserve would
         * answer "here is your hold" while nothing at all was set aside.
         *
         * O7 D1c is what surfaced it. A part-dispatched order releases its hold so the sale can take its own,
         * then re-holds the remainder under the SAME order key; under the old rule that re-hold silently
         * returned the dead row and the outstanding goods were left unprotected. RESERVED and CONFIRMED are
         * still returned as before: one is live, the other is a completed sale whose replay must not
         * double-count.
         */
        Reservation revive = null;
        if (req.getIdempotencyKey() != null) {
            var existing = reservationRepository.findByIdempotencyKeyScoped(req.getIdempotencyKey(), orgId, userId);
            if (existing.isPresent()) {
                if (existing.get().getStatus() != ReservationStatus.RELEASED) return toResponse(existing.get());
                // RE-ARM, not insert: `uq_resv_org_idem (organization_id, idempotency_key)` allows exactly one
                // row per key, so a second hold under the same key would violate it. The row IS the order's
                // hold, and it lives through reserve -> release -> reserve again.
                revive = existing.get();
            }
        }

        final LocalDate today = TenantClock.today();   // G1: FEFO excludes batches expired before today
        final boolean trackExpiry = capabilityService.isEnabled(com.myplus.common.settings.Capability.EXPIRY_TRACKING);

        /*
         * Pass 1 — verify EVERY product is fully satisfiable before holding anything (no partial holds).
         *
         * PR-3a — PER PRODUCT, SUMMED ACROSS LINES. It used to check each line on its own, so two lines of 5 against
         * 7 on the shelf both passed, and pass 2 then held 5 + 2 with nothing noticing the second line came up short:
         * the sale recorded 10 units with 7 held. The need is the total for the product.
         */
        java.util.Map<Long, List<StockEntry>> fefo = new java.util.HashMap<>();
        StockReservationResponse shortage = checkTotals(req, orgId, userId, today, trackExpiry, fefo);
        if (shortage != null) return shortage;

        /*
         * PR-3c — allocate IN MEMORY first, then hold exactly that. A pinned line (the sale priced it from that batch)
         * takes from its batch only; if the batch cannot cover it, NOTHING is held and the caller is told the stock
         * changed. Re-pricing it from another batch here would charge a price nobody showed the customer.
         */
        Allocation alloc = allocate(req.getLines(), fefo, true);
        if (alloc.refusal != null) return outOfStock(alloc.refusal);

        // Pass 2 — record the holds the allocation chose.
        final LocalDateTime deadline = reservationPolicy.expiryFor(orgId, LocalDateTime.now(),
                req.getHoldKind() == null
                        ? com.myplus.commerce.contracts.dto.StockReservationRequest.HoldKind.CHECKOUT
                        : req.getHoldKind());
        Reservation resv;
        if (revive != null) {
            // Same row, fresh promise: clear the spent picks, re-arm the status and take a new deadline.
            revive.getPicks().clear();
            revive.setStatus(ReservationStatus.RESERVED);
            revive.setExpiresAt(deadline);
            resv = revive;
        } else {
        resv = Reservation.builder()
                .reservationId(UUID.randomUUID().toString())
                .idempotencyKey(req.getIdempotencyKey())
                .status(ReservationStatus.RESERVED)
                .organizationId(orgId).userId(userId)
                // OMS O5a: a hold is a promise with a deadline. Without one, a reserve whose confirm or
                // compensating release never lands holds this stock FOREVER — availability is computed as
                // (quantity - reservedQuantity), so the stock stays counted in on-hand and is permanently
                // unsellable. Null when the tenant has switched expiry off.
                // O7 D1c: the deadline depends on WHAT KIND of promise this is. A confirmed order's hold
                // lives for days; a till's for minutes. A null kind reads as CHECKOUT, so every pre-D1c
                // caller keeps exactly the behaviour it had.
                .expiresAt(deadline)
                .picks(new ArrayList<>())
                .build();
        }

        for (Take t : alloc.takes) {
            StockEntry e = t.entry;
            e.setReservedQuantity(nz(e.getReservedQuantity()).add(t.quantity));
            stockEntryRepository.save(e);
            resv.addPick(ReservationPick.builder()
                    .stockEntryId(e.getId()).productId(t.productId)
                    .batchNo(e.getBatchNo()).quantity(t.quantity).expiryDate(e.getExpiryDate())
                    // #17 P3: stamp what this batch cost, HERE, where the batch is already in hand.
                    // Resolving it later would be a read per pick on the sale path.
                    .unitCost(unitCostOf(e))
                    .lineRef(t.lineRef)   // PR-3a: echoed on each pick taken for this line
                    .build());
        }
        reservationRepository.save(resv);
        return toResponse(resv);
    }

    /**
     * PR-3c — which batches a reserve of these lines would take RIGHT NOW, without holding anything.
     *
     * <p>The sale needs the batches before it can price a line in Per batch mode, and it must show the cashier the
     * same split it will charge. So it asks here first, prices from the answer, and then reserves exactly these
     * batches with each line PINNED — a pin that can no longer be met refuses the sale instead of re-pricing it.
     *
     * <p>A line's {@code stockEntryId} here is a PREFERENCE (the batch the cashier chose): taken first, the rest FEFO.
     * Read-only: the same rules as {@link #reserve} (expiry, quarantine, tenant scope), nothing written.
     */
    @Transactional(readOnly = true)
    public StockReservationResponse plan(StockReservationRequest req, Long orgId, Long userId) {
        final LocalDate today = TenantClock.today();
        final boolean trackExpiry = capabilityService.isEnabled(com.myplus.common.settings.Capability.EXPIRY_TRACKING);
        java.util.Map<Long, List<StockEntry>> fefo = new java.util.HashMap<>();
        StockReservationResponse shortage = checkTotals(req, orgId, userId, today, trackExpiry, fefo);
        if (shortage != null) return shortage;
        Allocation alloc = allocate(req.getLines(), fefo, false);
        if (alloc.refusal != null) return outOfStock(alloc.refusal);
        List<StockPick> picks = new ArrayList<>();
        for (Take t : alloc.takes) picks.add(pickOf(t.productId, t.entry, t.quantity, t.lineRef));
        return new StockReservationResponse(null, ReservationStatus.PLANNED, picks, null, null);
    }

    /** Pass 1, shared by reserve and plan: the product's total sellable stock against its total need. */
    private StockReservationResponse checkTotals(StockReservationRequest req, Long orgId, Long userId, LocalDate today,
                                                 boolean trackExpiry, java.util.Map<Long, List<StockEntry>> fefo) {
        java.util.Map<Long, BigDecimal> needByItem = new java.util.LinkedHashMap<>();
        for (StockReservationLine l : req.getLines())
            needByItem.merge(l.getItemId(), nz(l.getQuantity()), BigDecimal::add);
        for (java.util.Map.Entry<Long, BigDecimal> want : needByItem.entrySet()) {
            List<StockEntry> entries = stockEntryRepository.findForFefo(want.getKey(), orgId, userId, today, trackExpiry);
            fefo.put(want.getKey(), entries);
            BigDecimal need = want.getValue();
            BigDecimal available = BigDecimal.ZERO;
            for (StockEntry e : entries) available = available.add(availableOf(e));
            // U0: exact. The epsilon that used to pad this comparison existed only because float subtraction
            // never lands on zero — it also meant a shop was told it had stock it did not have, by up to one
            // epsilon. Exact decimals need no allowance and give no false yes.
            if (available.compareTo(need) < 0) {
                // Carry the numbers + productId so the sell orchestrator can render a friendly, name-resolved
                // message ("Not enough sellable stock for 'X': 7 sellable, 10 requested") instead of a raw 500.
                return outOfStock("product " + want.getKey() + ": only " + fmtQty(available)
                        + " sellable, " + fmtQty(need) + " requested");
            }
        }
        return null;
    }

    private static BigDecimal availableOf(StockEntry e) {
        return nz(e.getQuantity()).subtract(nz(e.getReservedQuantity())).max(BigDecimal.ZERO);
    }

    /** One planned take: so much of this batch, for that line. */
    private record Take(Long productId, StockEntry entry, BigDecimal quantity, Integer lineRef) {}

    /** The allocation, or the sentence that refuses it. */
    private record Allocation(List<Take> takes, String refusal) {}

    /**
     * The allocator, in memory, over the batches pass 1 loaded (so a batch is never double-counted between lines).
     *
     * <p>Pinned lines go FIRST, whatever their position: a FEFO line listed before them could otherwise eat the very
     * batch they were priced from. The takes are then returned in LINE order, each line's batches in the order taken.
     *
     * <p>{@code strictPins} — reserve: a pin is the only batch the line may take, and a pin that cannot be met refuses.
     * Plan: a pin is a preference, taken first, the rest FEFO.
     *
     * <p>U0 — exact allocation in BASE UNITS: the loop ends on a true zero, so no residue accumulates across batches
     * (a pack of 3, 6 or 7 sold loose would otherwise leave pieces that could never be allocated).
     */
    private Allocation allocate(List<StockReservationLine> lines, java.util.Map<Long, List<StockEntry>> fefo,
                                boolean strictPins) {
        java.util.Map<StockEntry, BigDecimal> left = new java.util.IdentityHashMap<>();
        for (List<StockEntry> es : fefo.values()) for (StockEntry e : es) left.put(e, availableOf(e));

        java.util.Map<Integer, List<Take>> byLine = new java.util.TreeMap<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).getStockEntryId() != null) order.add(i);
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).getStockEntryId() == null) order.add(i);

        for (int i : order) {
            StockReservationLine line = lines.get(i);
            List<Take> takes = byLine.computeIfAbsent(i, k -> new ArrayList<>());
            BigDecimal remaining = nz(line.getQuantity());
            List<StockEntry> entries = fefo.getOrDefault(line.getItemId(), List.of());
            if (line.getStockEntryId() != null) {
                // Scoped by construction: only this tenant's, this product's, sellable batches are in `entries`.
                StockEntry pinned = null;
                for (StockEntry e : entries) if (line.getStockEntryId().equals(e.getId())) pinned = e;
                BigDecimal avail = pinned == null ? BigDecimal.ZERO : left.get(pinned);
                if (strictPins && avail.compareTo(remaining) < 0) {
                    return new Allocation(null, "batch changed: product " + line.getItemId() + " batch "
                            + (pinned != null && pinned.getBatchNo() != null ? pinned.getBatchNo() : line.getStockEntryId())
                            + " has " + fmtQty(avail) + ", " + fmtQty(remaining) + " needed");
                }
                if (pinned != null && avail.signum() > 0) {
                    BigDecimal take = avail.min(remaining);
                    left.put(pinned, avail.subtract(take));
                    takes.add(new Take(line.getItemId(), pinned, take, line.getLineRef()));
                    remaining = remaining.subtract(take);
                }
            }
            for (StockEntry e : entries) {
                if (remaining.signum() <= 0) break;
                BigDecimal avail = left.get(e);
                if (avail.signum() <= 0) continue;
                BigDecimal take = avail.min(remaining);
                left.put(e, avail.subtract(take));
                takes.add(new Take(line.getItemId(), e, take, line.getLineRef()));
                remaining = remaining.subtract(take);
            }
            /*
             * PR-3a — a line that could not be held in full must never pass as reserved. Pass 1 makes this unreachable
             * for plain FEFO lines; with pins a batch-level shortfall is possible and refuses the whole request.
             */
            if (remaining.signum() > 0) {
                return new Allocation(null, "product " + line.getItemId() + ": only part of "
                        + fmtQty(nz(line.getQuantity())) + " could be held - stock changed while reserving; try again");
            }
        }
        List<Take> all = new ArrayList<>();
        for (List<Take> t : byLine.values()) all.addAll(t);
        return new Allocation(all, null);
    }

    private static StockPick pickOf(Long productId, StockEntry e, BigDecimal qty, Integer lineRef) {
        return new StockPick(productId, e.getBatchNo(), qty, e.getExpiryDate(), unitCostOf(e), lineRef,
                e.getId(), e.getSellPrice());
    }

    @Transactional
    public StockReservationResponse confirm(String reservationId, Long orgId, Long userId) {
        Reservation resv = load(reservationId, orgId, userId);
        if (resv.getStatus() == ReservationStatus.CONFIRMED) return toResponse(resv); // idempotent
        // OMS O5a: EXPIRED gets its own message. "Cannot confirm reservation in state EXPIRED" tells a cashier
        // nothing they can act on; this says what happened and what to do. The other states keep the generic
        // wording because they are programming errors, not situations a user can be in.
        if (resv.getStatus() == ReservationStatus.EXPIRED) {
            throw new ValidationException(
                    "That stock hold expired before the sale completed and the stock was returned to inventory. "
                            + "Please try the sale again.");
        }
        if (resv.getStatus() != ReservationStatus.RESERVED) {
            throw new ValidationException("Cannot confirm reservation in state " + resv.getStatus());
        }
        // Deliberately NOT checking expiresAt here. A hold past its deadline but not yet swept still physically
        // holds its stock, so nobody else can have taken it and confirming is safe. Refusing would fail sales
        // for no reason in the window between expiry and the next sweep. Only once the sweeper has actually
        // returned the stock (status EXPIRED, above) must confirm fail.
        for (ReservationPick p : resv.getPicks()) {
            stockEntryRepository.findById(p.getStockEntryId()).ifPresent(e -> {
                e.setQuantity(nz(e.getQuantity()).subtract(nz(p.getQuantity())));
                e.setReservedQuantity(nz(e.getReservedQuantity()).subtract(nz(p.getQuantity())).max(BigDecimal.ZERO));
                stockEntryRepository.save(e);
            });
            stockLevelRepository.findByProductScoped(p.getProductId(), orgId, userId).ifPresent(sl -> {
                sl.setCurrentStock(nz(sl.getCurrentStock()).subtract(nz(p.getQuantity())));
                stockLevelRepository.save(sl);
            });
        }
        resv.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(resv);
        return toResponse(resv);
    }

    @Transactional
    public StockReservationResponse release(String reservationId, Long orgId, Long userId) {
        Reservation resv = load(reservationId, orgId, userId);
        if (resv.getStatus() == ReservationStatus.RELEASED) return toResponse(resv); // idempotent
        if (resv.getStatus() == ReservationStatus.CONFIRMED) {
            throw new ValidationException("Cannot release a confirmed reservation (use a sale return)");
        }
        for (ReservationPick p : resv.getPicks()) {
            stockEntryRepository.findById(p.getStockEntryId()).ifPresent(e -> {
                e.setReservedQuantity(nz(e.getReservedQuantity()).subtract(nz(p.getQuantity())).max(BigDecimal.ZERO));
                stockEntryRepository.save(e);
            });
        }
        resv.setStatus(ReservationStatus.RELEASED);
        reservationRepository.save(resv);
        return toResponse(resv);
    }

    /**
     * G2 inverse saga (slice 34) — return sold stock for a (confirmed) reservation. Primary path: restore each
     * returned product to the sale's ORIGINAL batches (the reservation picks), capped per pick by
     * {@code quantity - returnedQuantity} so repeated partial returns never over-restore a batch — returned units
     * keep their real expiry, so FEFO stays correct and lot traceability holds. Fallback: when the reservation/picks
     * are unavailable (legacy/non-saga or the StockEntry is gone), or the returned qty exceeds what was picked,
     * the remainder re-enters via a fresh StockEntry. {@code StockLevel} is bumped by the full returned qty either way.
     */
    @Transactional
    public StockReturnResponse returnPicks(String reservationId, List<StockReturnLine> lines, boolean quarantine,
                                           Long orgId, Long userId) {
        Reservation resv = reservationRepository.findByReservationIdScoped(reservationId, orgId, userId).orElse(null);
        BigDecimal total = BigDecimal.ZERO;

        for (StockReturnLine line : lines) {
            if (line == null || line.getProductId() == null) continue;
            // U0 boundary: the CONTRACT still carries Float (StockReturnLine.qty) and U0 deliberately does
            // not change it — that is a six-service change with its own regression surface. Inventory converts
            // at its own edge, so what it STORES is exact even while what it is TOLD is not yet.
            BigDecimal qty = line.getQty() == null ? BigDecimal.ZERO : BigDecimal.valueOf(line.getQty());
            if (qty.signum() <= 0) continue;
            BigDecimal remaining = qty;

            if (resv != null) {
                for (ReservationPick p : resv.getPicks()) {
                    if (remaining.signum() <= 0) break;
                    if (!line.getProductId().equals(p.getProductId())) continue;
                    BigDecimal room = nz(p.getQuantity()).subtract(nz(p.getReturnedQuantity()));
                    if (room.signum() <= 0) continue;
                    BigDecimal take = room.min(remaining);
                    if (quarantine) {
                        // P11: returned med is NOT re-sellable — park it in a quarantine batch (keep lot/expiry).
                        createReturnEntry(line.getProductId(), take, p.getBatchNo(), p.getExpiryDate(), orgId, userId, false);
                    } else {
                        StockEntry e = stockEntryRepository.findById(p.getStockEntryId()).orElse(null);
                        if (e != null) {                   // restore to the exact original batch
                            e.setQuantity(nz(e.getQuantity()).add(take));
                            stockEntryRepository.save(e);
                        } else {                           // original batch gone -> fresh batch, keep its lot/expiry
                            createReturnEntry(line.getProductId(), take, p.getBatchNo(), p.getExpiryDate(), orgId, userId, true);
                        }
                    }
                    p.setReturnedQuantity(nz(p.getReturnedQuantity()).add(take));
                    remaining = remaining.subtract(take);
                }
            }

            if (remaining.signum() > 0) {                   // fallback: no picks / exhausted / beyond picked
                createReturnEntry(line.getProductId(), remaining, null, null, orgId, userId, !quarantine);
                remaining = BigDecimal.ZERO;
            }

            // Quarantined stock is physically present but NOT sellable, so it does not raise sellable on-hand.
            if (!quarantine) bumpLevel(line.getProductId(), qty, orgId, userId);
            total = total.add(qty);
        }

        if (resv != null) reservationRepository.save(resv);   // persist the per-pick returnedQuantity
        return new StockReturnResponse(reservationId, total, quarantine ? "QUARANTINED" : "RETURNED");
    }

    /** A fresh StockEntry for a return: carries the original lot/expiry when known; {@code restockable=false}
     *  quarantines it (P11) so FEFO/availability never re-sell it. */
    private void createReturnEntry(Long productId, BigDecimal qty, String batchNo, java.time.LocalDate expiry,
                                   Long orgId, Long userId, boolean restockable) {
        stockEntryRepository.save(StockEntry.builder()
                .productId(productId).quantity(nz(qty)).reservedQuantity(BigDecimal.ZERO)
                .batchNo(batchNo).expiryDate(expiry).restockable(restockable)
                .organizationId(orgId).userId(userId).build());
    }

    /** Make the product's on-hand whole again: StockLevel += qty, creating a zero level for the tenant if missing. */
    private void bumpLevel(Long productId, BigDecimal qty, Long orgId, Long userId) {
        StockLevel level = stockLevelRepository.findByProductScoped(productId, orgId, userId)
                .orElseGet(() -> StockLevel.builder()
                        .productId(productId).currentStock(BigDecimal.ZERO)
                        .organizationId(orgId).userId(userId).build());
        level.setCurrentStock(nz(level.getCurrentStock()).add(nz(qty)));
        stockLevelRepository.save(level);
    }

    private Reservation load(String reservationId, Long orgId, Long userId) {
        return reservationRepository.findByReservationIdScoped(reservationId, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
    }

    private StockReservationResponse outOfStock(String message) {
        return new StockReservationResponse(null, ReservationStatus.OUT_OF_STOCK, List.of(), message);
    }

    /**
     * #17 P3 — what one unit of a batch cost.
     *
     * <p>ALLOCATED from the batch total where P2 recorded one, because that is the only figure that
     * reconciles when a supplier bonus made the received quantity differ from the billed one: 5,000 paid for
     * 11 units is 454.5454..., not the 500 on the invoice line.
     *
     * <p>Falls back to the unit purchase price for batches received before P2, where quantity x price IS the
     * total and always was — no bonus was involved, so the identity holds exactly.
     *
     * <h3>⚠ COGS-1 — divided by what was RECEIVED, never by what is LEFT</h3>
     * This used to divide by {@code getQuantity()}, which is the batch's REMAINING stock and falls on every sale,
     * while {@code paidTotal} is what the whole batch cost and never changes. So each sale after the first was
     * costed higher than the one before — 800 paid for 10: the first sale at 80.00, the next at 800/8 = 100.00 —
     * and on the dev data one batch climbed from 500 to 10,000 per unit over 14 sales. Cost of goods was
     * overstated and Inventory understated by the same amount in the same journal, so the trial balance stayed
     * balanced and nothing looked wrong. The E2E flow gate found it only by reading the journal lines.
     *
     * <p>A batch with a paid total but no received quantity cannot occur after V12's backfill. If one ever does, it
     * costs from the unit purchase price — the billed rate, exact whenever no bonus was involved — and never from
     * the remaining quantity, which is the one divisor known to be wrong.
     *
     * <p>Package-private and static so the rule is tested on its own, without a database.
     */
    static BigDecimal unitCostOf(StockEntry e) {
        if (e == null) return null;
        if (e.getPaidTotal() != null && e.getReceivedQuantity() != null && e.getReceivedQuantity().signum() > 0)
            return e.getPaidTotal().divide(e.getReceivedQuantity(), 6, java.math.RoundingMode.HALF_UP);
        return e.getPurchasePrice();
    }

    private StockReservationResponse toResponse(Reservation resv) {
        List<StockPick> picks = new ArrayList<>();
        for (ReservationPick p : resv.getPicks()) {
            picks.add(new StockPick(p.getProductId(), p.getBatchNo(), nz(p.getQuantity()), p.getExpiryDate(),
                    p.getUnitCost(), p.getLineRef(), p.getStockEntryId(), null));
        }
        return new StockReservationResponse(resv.getReservationId(), resv.getStatus(), picks, null,
                resv.getExpiresAt());
    }
}
