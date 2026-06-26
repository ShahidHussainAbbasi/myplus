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

    private static float nz(Float f) { return f == null ? 0f : f; }
    private static final float EPS = 0.0001f;

    @Transactional
    public StockReservationResponse reserve(StockReservationRequest req, Long orgId, Long userId) {
        // Idempotency: a retried reserve with the same key returns the existing hold, never double-holds.
        if (req.getIdempotencyKey() != null) {
            var existing = reservationRepository.findByIdempotencyKeyScoped(req.getIdempotencyKey(), orgId, userId);
            if (existing.isPresent()) return toResponse(existing.get());
        }

        final LocalDate today = LocalDate.now();   // G1: FEFO excludes batches expired before today

        // Pass 1 — verify EVERY line is fully satisfiable before holding anything (no partial holds).
        for (StockReservationLine line : req.getLines()) {
            float need = line.getQuantity().floatValue();
            float available = 0f;
            for (StockEntry e : stockEntryRepository.findForFefo(line.getItemId(), orgId, userId, today)) {
                available += Math.max(0f, nz(e.getQuantity()) - nz(e.getReservedQuantity()));
            }
            if (available + EPS < need) {
                return outOfStock("Insufficient stock for product " + line.getItemId());
            }
        }

        // Pass 2 — allocate FEFO and record the holds.
        Reservation resv = Reservation.builder()
                .reservationId(UUID.randomUUID().toString())
                .idempotencyKey(req.getIdempotencyKey())
                .status(ReservationStatus.RESERVED)
                .organizationId(orgId).userId(userId)
                .picks(new ArrayList<>())
                .build();

        for (StockReservationLine line : req.getLines()) {
            float remaining = line.getQuantity().floatValue();
            for (StockEntry e : stockEntryRepository.findForFefo(line.getItemId(), orgId, userId, today)) {
                if (remaining <= EPS) break;
                float avail = nz(e.getQuantity()) - nz(e.getReservedQuantity());
                if (avail <= 0f) continue;
                float take = Math.min(avail, remaining);
                e.setReservedQuantity(nz(e.getReservedQuantity()) + take);
                stockEntryRepository.save(e);
                resv.addPick(ReservationPick.builder()
                        .stockEntryId(e.getId()).productId(line.getItemId())
                        .batchNo(e.getBatchNo()).quantity(take).expiryDate(e.getExpiryDate())
                        .build());
                remaining -= take;
            }
        }
        reservationRepository.save(resv);
        return toResponse(resv);
    }

    @Transactional
    public StockReservationResponse confirm(String reservationId, Long orgId, Long userId) {
        Reservation resv = load(reservationId, orgId, userId);
        if (resv.getStatus() == ReservationStatus.CONFIRMED) return toResponse(resv); // idempotent
        if (resv.getStatus() != ReservationStatus.RESERVED) {
            throw new ValidationException("Cannot confirm reservation in state " + resv.getStatus());
        }
        for (ReservationPick p : resv.getPicks()) {
            stockEntryRepository.findById(p.getStockEntryId()).ifPresent(e -> {
                e.setQuantity(nz(e.getQuantity()) - p.getQuantity());
                e.setReservedQuantity(Math.max(0f, nz(e.getReservedQuantity()) - p.getQuantity()));
                stockEntryRepository.save(e);
            });
            stockLevelRepository.findByProductScoped(p.getProductId(), orgId, userId).ifPresent(sl -> {
                sl.setCurrentStock(nz(sl.getCurrentStock()) - p.getQuantity());
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
                e.setReservedQuantity(Math.max(0f, nz(e.getReservedQuantity()) - p.getQuantity()));
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
        float total = 0f;

        for (StockReturnLine line : lines) {
            if (line == null || line.getProductId() == null) continue;
            float qty = nz(line.getQty());
            if (qty <= EPS) continue;
            float remaining = qty;

            if (resv != null) {
                for (ReservationPick p : resv.getPicks()) {
                    if (remaining <= EPS) break;
                    if (!line.getProductId().equals(p.getProductId())) continue;
                    float room = nz(p.getQuantity()) - nz(p.getReturnedQuantity());
                    if (room <= 0f) continue;
                    float take = Math.min(room, remaining);
                    if (quarantine) {
                        // P11: returned med is NOT re-sellable — park it in a quarantine batch (keep lot/expiry).
                        createReturnEntry(line.getProductId(), take, p.getBatchNo(), p.getExpiryDate(), orgId, userId, false);
                    } else {
                        StockEntry e = stockEntryRepository.findById(p.getStockEntryId()).orElse(null);
                        if (e != null) {                   // restore to the exact original batch
                            e.setQuantity(nz(e.getQuantity()) + take);
                            stockEntryRepository.save(e);
                        } else {                           // original batch gone -> fresh batch, keep its lot/expiry
                            createReturnEntry(line.getProductId(), take, p.getBatchNo(), p.getExpiryDate(), orgId, userId, true);
                        }
                    }
                    p.setReturnedQuantity(nz(p.getReturnedQuantity()) + take);
                    remaining -= take;
                }
            }

            if (remaining > EPS) {                          // fallback: no picks / exhausted / beyond picked
                createReturnEntry(line.getProductId(), remaining, null, null, orgId, userId, !quarantine);
                remaining = 0f;
            }

            // Quarantined stock is physically present but NOT sellable, so it does not raise sellable on-hand.
            if (!quarantine) bumpLevel(line.getProductId(), qty, orgId, userId);
            total += qty;
        }

        if (resv != null) reservationRepository.save(resv);   // persist the per-pick returnedQuantity
        return new StockReturnResponse(reservationId, BigDecimal.valueOf(total), quarantine ? "QUARANTINED" : "RETURNED");
    }

    /** A fresh StockEntry for a return: carries the original lot/expiry when known; {@code restockable=false}
     *  quarantines it (P11) so FEFO/availability never re-sell it. */
    private void createReturnEntry(Long productId, float qty, String batchNo, java.time.LocalDate expiry,
                                   Long orgId, Long userId, boolean restockable) {
        stockEntryRepository.save(StockEntry.builder()
                .productId(productId).quantity(qty).reservedQuantity(0f)
                .batchNo(batchNo).expiryDate(expiry).restockable(restockable)
                .organizationId(orgId).userId(userId).build());
    }

    /** Make the product's on-hand whole again: StockLevel += qty, creating a zero level for the tenant if missing. */
    private void bumpLevel(Long productId, float qty, Long orgId, Long userId) {
        StockLevel level = stockLevelRepository.findByProductScoped(productId, orgId, userId)
                .orElseGet(() -> StockLevel.builder()
                        .productId(productId).currentStock(0f)
                        .organizationId(orgId).userId(userId).build());
        level.setCurrentStock(nz(level.getCurrentStock()) + qty);
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
            picks.add(new StockPick(p.getProductId(), p.getBatchNo(), BigDecimal.valueOf(p.getQuantity()), p.getExpiryDate()));
        }
        return new StockReservationResponse(resv.getReservationId(), resv.getStatus(), picks, null);
    }
}
