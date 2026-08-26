package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.inventory.entity.Reservation;
import com.myplus.inventory.entity.ReservationPick;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.repository.ReservationRepository;
import com.myplus.inventory.repository.StockEntryRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OMS O5a — what the sweeper frees, and (more importantly) what it refuses to.
 *
 * <p>The dangerous direction is not "a leak survives another five minutes". It is the sweeper returning stock
 * that has just been SOLD, which turns a stock shortage into a stock overstatement and makes the shop oversell.
 * Most of these cases guard that.
 */
@ExtendWith(MockitoExtension.class)
class SweeperSelectionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 12, 0);

    @Mock private ReservationRepository reservationRepository;
    @Mock private StockEntryRepository stockEntryRepository;
    @InjectMocks private ReservationExpiryWorker worker;

    private Reservation hold(ReservationStatus status, LocalDateTime expiresAt) {
        Reservation r = Reservation.builder()
                .id(1L).reservationId("resv-1").organizationId(7L)
                .status(status).expiresAt(expiresAt)
                .picks(new java.util.ArrayList<>())
                .build();
        r.addPick(ReservationPick.builder().stockEntryId(50L).productId(9L).quantity(java.math.BigDecimal.valueOf(3)).build());
        return r;
    }

    private StockEntry entry(double qty, double reserved) {
        StockEntry e = new StockEntry();
        e.setId(50L);
        e.setQuantity(java.math.BigDecimal.valueOf(qty));
        e.setReservedQuantity(java.math.BigDecimal.valueOf(reserved));
        return e;
    }

    // ── the happy path: a genuine leak is freed ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an expired RESERVED hold returns its stock and is marked EXPIRED")
    void expiredHoldIsFreed() {
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RESERVED, NOW.minusMinutes(1))));
        StockEntry e = entry(10f, 3f);
        when(stockEntryRepository.findById(50L)).thenReturn(Optional.of(e));

        assertThat(worker.expireOne(1L, NOW)).isTrue();

        // The hold comes off reservedQuantity; the physical quantity is untouched, because nothing was sold.
        assertThat(e.getReservedQuantity()).isEqualByComparingTo("0");
        assertThat(e.getQuantity()).as("expiry returns a HOLD, it does not move stock").isEqualByComparingTo("10");

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus())
                .as("EXPIRED, not RELEASED — 'nobody came back' is a different fact from 'the caller cancelled'")
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    // ── what it must refuse ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a hold CONFIRMED since the candidate query is left alone — this is the oversell guard")
    void confirmedSinceSelectionIsSkipped() {
        // The race: selected as a candidate, then a confirm lands, then the sweeper gets the lock. Freeing it
        // now would return stock that was just sold. The status is re-read under the lock precisely for this.
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.CONFIRMED, NOW.minusMinutes(1))));

        assertThat(worker.expireOne(1L, NOW)).isFalse();

        verify(stockEntryRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("an already RELEASED hold is not freed twice")
    void releasedIsSkipped() {
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RELEASED, NOW.minusMinutes(1))));
        assertThat(worker.expireOne(1L, NOW)).isFalse();
        verify(stockEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("an already EXPIRED hold is idempotent — a second sweeper finds nothing to do")
    void expiredIsIdempotent() {
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.EXPIRED, NOW.minusMinutes(1))));
        assertThat(worker.expireOne(1L, NOW)).isFalse();
        verify(stockEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a hold that is not yet due is left alone")
    void notYetDueIsSkipped() {
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RESERVED, NOW.plusMinutes(10))));
        assertThat(worker.expireOne(1L, NOW)).isFalse();
        verify(stockEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a hold exactly at its deadline is not yet expired")
    void boundaryIsNotExpired() {
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RESERVED, NOW)));
        assertThat(worker.expireOne(1L, NOW)).as("strictly after — the full hold is honoured").isFalse();
    }

    @Test
    @DisplayName("a hold with NO deadline is never swept")
    void noDeadlineIsNeverSwept() {
        // Expiry switched off for this tenant, or a row written before V6. Neither should vanish because a
        // migration ran.
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RESERVED, null)));
        assertThat(worker.expireOne(1L, NOW)).isFalse();
        verify(stockEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a reservation that vanished between selection and lock is a no-op, not a crash")
    void missingReservationIsSafe() {
        when(reservationRepository.lockById(1L)).thenReturn(Optional.empty());
        assertThat(worker.expireOne(1L, NOW)).isFalse();
    }

    // ── arithmetic safety ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returning a hold never drives reservedQuantity negative")
    void heldNeverGoesNegative() {
        // If some earlier inconsistency already reduced the hold, subtracting again would produce a NEGATIVE
        // reserved quantity — and since availability is (quantity - reserved), that would INFLATE what the shop
        // believes it can sell. Freeing stock must never invent any.
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RESERVED, NOW.minusMinutes(1))));
        StockEntry e = entry(10f, 1f);          // only 1 held, but the pick says 3
        when(stockEntryRepository.findById(50L)).thenReturn(Optional.of(e));

        assertThat(worker.expireOne(1L, NOW)).isTrue();
        assertThat(e.getReservedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a pick whose stock entry is gone does not stop the hold being closed")
    void missingStockEntryStillClosesTheHold() {
        when(reservationRepository.lockById(1L))
                .thenReturn(Optional.of(hold(ReservationStatus.RESERVED, NOW.minusMinutes(1))));
        when(stockEntryRepository.findById(50L)).thenReturn(Optional.empty());

        // Leaving it RESERVED would mean the sweeper retries this row forever, every five minutes.
        assertThat(worker.expireOne(1L, NOW)).isTrue();
        verify(reservationRepository).save(any());
    }

    // ── the sweeper's batching ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a pass is bounded and counts only the holds it actually freed")
    void sweepIsBoundedAndCountsHonestly() {
        ReservationExpiryWorker stubWorker = org.mockito.Mockito.mock(ReservationExpiryWorker.class);
        ExpiredReservationSweeper sweeper = new ExpiredReservationSweeper(reservationRepository, stubWorker);
        org.springframework.test.util.ReflectionTestUtils.setField(sweeper, "batchSize", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(sweeper, "enabled", true);

        Reservation a = hold(ReservationStatus.RESERVED, NOW.minusMinutes(1));
        Reservation b = hold(ReservationStatus.RESERVED, NOW.minusMinutes(2));
        b.setId(2L);
        when(reservationRepository.findExpired(eq(NOW), any())).thenReturn(List.of(a, b));
        when(stubWorker.expireOne(1L, NOW)).thenReturn(true);
        when(stubWorker.expireOne(2L, NOW)).thenReturn(false);   // lost the race — must not be counted

        assertThat(sweeper.sweepBatch(NOW))
                .as("only genuinely freed holds are reported")
                .isEqualTo(1);

        ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(reservationRepository).findExpired(eq(NOW), page.capture());
        assertThat(page.getValue().getPageSize())
                .as("the first run after shipping may meet months of backlog; it must not take it all at once")
                .isEqualTo(2);
    }
}
