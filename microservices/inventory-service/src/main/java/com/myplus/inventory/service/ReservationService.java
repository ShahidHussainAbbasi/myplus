package com.myplus.inventory.service;

import com.myplus.commerce.contracts.dto.*;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.inventory.entity.Reservation;
import com.myplus.inventory.entity.ReservationPick;
import com.myplus.inventory.entity.StockEntry;
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
