package com.myplus.inventory.service;

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

        final LocalDate today = LocalDate.now();   // G1: FEFO excludes batches expired before today

        // Pass 1 — verify EVERY line is fully satisfiable before holding anything (no partial holds).
        for (StockReservationLine line : req.getLines()) {
            BigDecimal need = nz(line.getQuantity());
            BigDecimal available = BigDecimal.ZERO;
            for (StockEntry e : stockEntryRepository.findForFefo(line.getItemId(), orgId, userId, today)) {
                available = available.add(nz(e.getQuantity()).subtract(nz(e.getReservedQuantity())).max(BigDecimal.ZERO));
            }
            // U0: exact. The epsilon that used to pad this comparison existed only because float subtraction
            // never lands on zero — it also meant a shop was told it had stock it did not have, by up to one
            // epsilon. Exact decimals need no allowance and give no false yes.
            if (available.compareTo(need) < 0) {
                // Carry the numbers + productId so the sell orchestrator can render a friendly, name-resolved
                // message ("Not enough sellable stock for 'X': 7 sellable, 10 requested") instead of a raw 500.
                return outOfStock("product " + line.getItemId() + ": only " + fmtQty(available)
                        + " sellable, " + fmtQty(need) + " requested");
            }
        }

        // Pass 2 — allocate FEFO and record the holds.
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

        for (StockReservationLine line : req.getLines()) {
            /*
             * U0 — exact allocation, in BASE UNITS.
             *
             * ⚠ THE `.floatValue()` HERE WAS THROWING PRECISION AWAY AT THE BOUNDARY. The request already
             * arrives as BigDecimal; converting to float on entry and comparing with an epsilon on every
             * iteration was the only reason the tolerance existed. With exact decimals the loop terminates on
             * a true zero, so `remaining.signum() <= 0` says what it means and no residue can accumulate
             * across batches.
             *
             * That matters most exactly where loose selling will land: a pack of 3, 6 or 7 leaves a float
             * remainder that never reaches zero, so the last pieces of a batch could never be allocated.
             */
            BigDecimal remaining = nz(line.getQuantity());
            for (StockEntry e : stockEntryRepository.findForFefo(line.getItemId(), orgId, userId, today)) {
                if (remaining.signum() <= 0) break;
                BigDecimal avail = nz(e.getQuantity()).subtract(nz(e.getReservedQuantity()));
                if (avail.signum() <= 0) continue;
                BigDecimal take = avail.min(remaining);
                e.setReservedQuantity(nz(e.getReservedQuantity()).add(take));
                stockEntryRepository.save(e);
                resv.addPick(ReservationPick.builder()
                        .stockEntryId(e.getId()).productId(line.getItemId())
                        .batchNo(e.getBatchNo()).quantity(take).expiryDate(e.getExpiryDate())
                        .build());
                remaining = remaining.subtract(take);
            }
        }
        reservationRepository.save(resv);
        return toResponse(resv);
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

    private StockReservationResponse toResponse(Reservation resv) {
        List<StockPick> picks = new ArrayList<>();
        for (ReservationPick p : resv.getPicks()) {
            picks.add(new StockPick(p.getProductId(), p.getBatchNo(), nz(p.getQuantity()), p.getExpiryDate()));
        }
        return new StockReservationResponse(resv.getReservationId(), resv.getStatus(), picks, null,
                resv.getExpiresAt());
    }
}
