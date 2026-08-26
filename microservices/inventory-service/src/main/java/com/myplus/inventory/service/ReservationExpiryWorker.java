package com.myplus.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.inventory.entity.Reservation;
import com.myplus.inventory.entity.ReservationPick;
import com.myplus.inventory.repository.ReservationRepository;
import com.myplus.inventory.repository.StockEntryRepository;

import lombok.RequiredArgsConstructor;

/**
 * OMS O5a — frees exactly one expired hold, in its own transaction.
 *
 * <h3>Why this is a separate bean and not a method on the sweeper</h3>
 * Spring's {@code @Transactional} is proxy-based, so a call from one method of a bean to another method of the
 * SAME bean does not pass through the proxy and the annotation does nothing. Had {@code expireOne} stayed on
 * {@link ExpiredReservationSweeper}, its {@code REQUIRES_NEW} would have been silently ignored: the whole batch
 * would have run in one transaction, holding locks across every row and rolling back all freed holds if any
 * single one failed. Splitting the collaborator out is what makes the annotation real.
 */
@Service
@RequiredArgsConstructor
public class ReservationExpiryWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryWorker.class);

    private final ReservationRepository reservationRepository;
    private final StockEntryRepository stockEntryRepository;

    /**
     * Return one hold's stock, or decline to.
     *
     * <p>{@code REQUIRES_NEW} so one stuck row cannot hold a lock across the whole batch, and one failure cannot
     * roll back holds already freed — this is a repair job, and partial progress beats none.
     *
     * @return true when this call actually freed the hold
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(Long id, LocalDateTime now) {
        Reservation resv = reservationRepository.lockById(id).orElse(null);
        if (resv == null) return false;

        // Re-checked UNDER THE LOCK: a confirm or release may have landed since the candidate query. Freeing a
        // hold that has just been confirmed would return stock that was sold — a leak turned into an oversell.
        if (resv.getStatus() != ReservationStatus.RESERVED) return false;
        if (resv.getExpiresAt() == null || !now.isAfter(resv.getExpiresAt())) return false;

        for (ReservationPick p : resv.getPicks()) {
            stockEntryRepository.findById(p.getStockEntryId()).ifPresent(e -> {
                BigDecimal held = e.getReservedQuantity() == null ? BigDecimal.ZERO : e.getReservedQuantity();
                BigDecimal took = p.getQuantity() == null ? BigDecimal.ZERO : p.getQuantity();
                // Floored at zero: this only ever gives stock back, so a double-subtraction from some earlier
                // inconsistency must not become a negative hold, which would inflate availability instead.
                e.setReservedQuantity(held.subtract(took).max(BigDecimal.ZERO));
                stockEntryRepository.save(e);
            });
        }
        resv.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.save(resv);

        // WARN, not INFO: every one of these is stock that was silently unsellable, and a rising count means a
        // caller upstream is failing to compensate. That is worth noticing, not just counting.
        LOG.warn("OMS O5a: hold {} (org {}) expired at {} without confirm or release — stock returned",
                resv.getReservationId(), resv.getOrganizationId(), resv.getExpiresAt());
        return true;
    }
}
