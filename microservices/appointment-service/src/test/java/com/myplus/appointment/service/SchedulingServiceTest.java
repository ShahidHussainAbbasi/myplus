package com.myplus.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.myplus.appointment.entity.Slot;
import com.myplus.appointment.repository.BookingRepository;
import com.myplus.appointment.repository.SlotRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * SCHED-2 — publishing an evening is one read and one write.
 *
 * <p>It was one transaction per slot inside an outer transaction, with every existing slot found by letting its
 * INSERT fail. On a cold first call that overran education's 3 s read timeout and the school saw ERROR for slots
 * that had been made. These pin the new shape: read the published start times once, insert only what is missing
 * in one batch, and fall back slot-by-slot only when a concurrent publish wins the race.
 */
@ExtendWith(MockitoExtension.class)
class SchedulingServiceTest {

    private static final Long ORG = 14L, TEACHER = 2L;
    private static final LocalDateTime FROM = LocalDateTime.of(2027, 3, 15, 18, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2027, 3, 15, 19, 0);   // 6 slots of 10 minutes

    @Mock private SlotRepository slotRepo;
    @Mock private BookingRepository bookingRepo;
    @Mock private ObjectProvider<SchedulingService> self;
    /** The transactional proxy the service calls itself through. */
    @Mock private SchedulingService tx;

    private SchedulingService service;

    @BeforeEach
    void setUp() {
        service = new SchedulingService(slotRepo, bookingRepo, self);
        lenient().when(self.getObject()).thenReturn(tx);
    }

    private static Slot publishedAt(int hour, int minute) {
        Slot s = new Slot();
        s.setStartsAt(LocalDateTime.of(2027, 3, 15, hour, minute));
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<SlotConflictDetector.Window> batchSaved() {
        ArgumentCaptor<List<SlotConflictDetector.Window>> c = ArgumentCaptor.forClass(List.class);
        verify(tx).saveSlots(eq(ORG), eq(TEACHER), any(), eq("MEET-1"), c.capture(), eq(1));
        return c.getValue();
    }

    @Test
    @DisplayName("a fresh evening is ONE batch write — no per-slot transactions, no exceptions")
    void freshEveningIsOneBatch() {
        when(slotRepo.findByProviderInWindow(eq(TEACHER), any(), any(), eq(ORG))).thenReturn(List.of());

        Map<String, Object> out = service.generate(ORG, TEACHER, null, "MEET-1", FROM, TO, 10, 1);

        assertThat(out).containsEntry("created", 6).containsEntry("alreadyExisted", 0);
        assertThat(batchSaved()).hasSize(6);
        verify(tx, never()).saveSlot(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("extending an evening inserts ONLY the new part, and counts the rest without failing an insert")
    void extendingInsertsOnlyTheMissingPart() {
        // 18:00, 18:10 and 18:20 were published earlier.
        when(slotRepo.findByProviderInWindow(eq(TEACHER), any(), any(), eq(ORG)))
                .thenReturn(List.of(publishedAt(18, 0), publishedAt(18, 10), publishedAt(18, 20)));

        Map<String, Object> out = service.generate(ORG, TEACHER, null, "MEET-1", FROM, TO, 10, 1);

        assertThat(out).containsEntry("created", 3).containsEntry("alreadyExisted", 3);
        assertThat(batchSaved()).extracting(SlotConflictDetector.Window::startsAt)
                .containsExactly(LocalDateTime.of(2027, 3, 15, 18, 30), LocalDateTime.of(2027, 3, 15, 18, 40),
                        LocalDateTime.of(2027, 3, 15, 18, 50));
    }

    @Test
    @DisplayName("re-publishing the same evening writes NOTHING and reports every slot as already there")
    void republishWritesNothing() {
        when(slotRepo.findByProviderInWindow(eq(TEACHER), any(), any(), eq(ORG))).thenReturn(List.of(
                publishedAt(18, 0), publishedAt(18, 10), publishedAt(18, 20),
                publishedAt(18, 30), publishedAt(18, 40), publishedAt(18, 50)));

        Map<String, Object> out = service.generate(ORG, TEACHER, null, "MEET-1", FROM, TO, 10, 1);

        assertThat(out).containsEntry("created", 0).containsEntry("alreadyExisted", 6);
        verify(tx, never()).saveSlots(any(), any(), any(), any(), anyList(), anyInt());
    }

    @Test
    @DisplayName("a concurrent publish that wins the race falls back slot-by-slot and still counts correctly")
    void raceFallsBackSlotBySlot() {
        when(slotRepo.findByProviderInWindow(eq(TEACHER), any(), any(), eq(ORG))).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("uk_slot_provider_time"))
                .when(tx).saveSlots(any(), any(), any(), any(), anyList(), anyInt());
        // The other publish got 18:00 in first; the rest are still free.
        doThrow(new DataIntegrityViolationException("uk_slot_provider_time"))
                .when(tx).saveSlot(any(), any(), any(), any(),
                        eq(new SlotConflictDetector.Window(FROM, FROM.plusMinutes(10))), anyInt());
        when(slotRepo.existsByOrganizationIdAndProviderIdAndStartsAt(ORG, TEACHER, FROM)).thenReturn(true);

        Map<String, Object> out = service.generate(ORG, TEACHER, null, "MEET-1", FROM, TO, 10, 1);

        assertThat(out).containsEntry("created", 5).containsEntry("alreadyExisted", 1);
        verify(tx, times(6)).saveSlot(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a violation that is NOT the slot key is not counted as 'already existed'")
    void unrelatedViolationStillFails() {
        when(slotRepo.findByProviderInWindow(eq(TEACHER), any(), any(), eq(ORG))).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("something else"))
                .when(tx).saveSlots(any(), any(), any(), any(), anyList(), anyInt());
        doThrow(new DataIntegrityViolationException("something else"))
                .when(tx).saveSlot(any(), any(), any(), any(), any(), anyInt());
        when(slotRepo.existsByOrganizationIdAndProviderIdAndStartsAt(eq(ORG), eq(TEACHER), any())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.generate(ORG, TEACHER, null, "MEET-1", FROM, TO, 10, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
