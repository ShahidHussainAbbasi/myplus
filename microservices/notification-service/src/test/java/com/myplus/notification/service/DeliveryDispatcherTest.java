package com.myplus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.myplus.common.notify.EmailRequest;
import com.myplus.notification.entity.DeliveryStatus;
import com.myplus.notification.entity.NotificationBroadcast;
import com.myplus.notification.entity.NotificationDelivery;
import com.myplus.notification.repository.NotificationBroadcastRepository;
import com.myplus.notification.repository.NotificationDeliveryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Slice 105 — the retry relay. Covers the two rules that would fail silently if they regressed: a bad row
 * must not stop the batch, and a dead address must eventually stop being retried.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryDispatcherTest {

    @Mock private NotificationDeliveryRepository deliveryRepo;
    @Mock private NotificationBroadcastRepository broadcastRepo;
    @Mock private NotificationService notificationService;
    @Mock private DeliveryRecorder recorder;
    @InjectMocks private DeliveryDispatcher dispatcher;

    @BeforeEach
    void config() {
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 5);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 50);
    }

    private NotificationDelivery pending(long id, int attempts) {
        return NotificationDelivery.builder()
                .id(id).broadcastId(100L).organizationId(3L).recipient("a" + id + "@test.com")
                .status(DeliveryStatus.PENDING).attempts(attempts).build();
    }

    @Test
    void resends_a_pending_delivery_with_the_original_subject_and_body() {
        when(deliveryRepo.findDueForDispatch(eq(DeliveryStatus.PENDING), anyInt(), any()))
                .thenReturn(List.of(pending(1L, 1)));
        when(broadcastRepo.findById(100L)).thenReturn(Optional.of(NotificationBroadcast.builder()
                .id(100L).subject("Closure").body("School closed Friday").build()));
        when(notificationService.deliver(any())).thenReturn(new NotificationService.SendResult(true, null));

        dispatcher.dispatchPending();

        ArgumentCaptor<EmailRequest> sent = ArgumentCaptor.forClass(EmailRequest.class);
        verify(notificationService).deliver(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("a1@test.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Closure");
        verify(recorder).settleOne(any(), eq(true), eq(null));
    }

    @Test
    void one_failing_row_does_not_stop_the_rest_of_the_batch() {
        when(deliveryRepo.findDueForDispatch(eq(DeliveryStatus.PENDING), anyInt(), any()))
                .thenReturn(List.of(pending(1L, 0), pending(2L, 0), pending(3L, 0)));
        when(broadcastRepo.findById(100L)).thenReturn(Optional.of(
                NotificationBroadcast.builder().id(100L).subject("S").body("B").build()));
        when(notificationService.deliver(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(new NotificationService.SendResult(true, null),
                            new NotificationService.SendResult(true, null));

        dispatcher.dispatchPending();

        // The other two families are still waiting on their notice — one bad address must not strand them.
        verify(notificationService, times(3)).deliver(any());
    }

    @Test
    void gives_up_once_the_attempt_cap_is_reached() {
        NotificationDelivery exhausted = pending(1L, 5);
        when(deliveryRepo.findDueForDispatch(eq(DeliveryStatus.PENDING), anyInt(), any()))
                .thenReturn(List.of(exhausted));
        when(broadcastRepo.findById(100L)).thenReturn(Optional.of(
                NotificationBroadcast.builder().id(100L).subject("S").body("B").build()));
        when(notificationService.deliver(any())).thenReturn(new NotificationService.SendResult(false, "smtp refused"));
        when(deliveryRepo.findById(1L)).thenReturn(Optional.of(exhausted));

        dispatcher.dispatchPending();

        // FAILED, not PENDING-forever: a row that never resolves is indistinguishable from one about to
        // succeed, which is exactly the ambiguity the school is asking about when they ring.
        assertThat(exhausted.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void a_delivery_already_sent_is_never_downgraded_to_failed() {
        NotificationDelivery d = pending(1L, 5);
        when(deliveryRepo.findDueForDispatch(eq(DeliveryStatus.PENDING), anyInt(), any()))
                .thenReturn(List.of(d));
        when(broadcastRepo.findById(100L)).thenReturn(Optional.of(
                NotificationBroadcast.builder().id(100L).subject("S").body("B").build()));
        when(notificationService.deliver(any())).thenReturn(new NotificationService.SendResult(false, "smtp refused"));
        NotificationDelivery alreadySent = NotificationDelivery.builder()
                .id(1L).status(DeliveryStatus.SENT).attempts(5).build();
        when(deliveryRepo.findById(1L)).thenReturn(Optional.of(alreadySent));

        dispatcher.dispatchPending();

        // Re-reads before deciding, because another pass may have succeeded in between. Marking a
        // delivered message FAILED would send the office chasing a notice the family already has.
        assertThat(alreadySent.getStatus()).isEqualTo(DeliveryStatus.SENT);
        verify(deliveryRepo, never()).save(alreadySent);
    }

    @Test
    void an_unreadable_queue_does_not_crash_the_scheduled_pass() {
        when(deliveryRepo.findDueForDispatch(any(), anyInt(), any()))
                .thenThrow(new RuntimeException("db down"));

        // An uncaught exception from a @Scheduled method silently cancels a fixedDelay schedule: the relay
        // would never run again until restart, and nothing would say so.
        dispatcher.dispatchPending();

        verify(notificationService, never()).deliver(any());
    }

    @Test
    void does_nothing_when_the_queue_is_empty() {
        when(deliveryRepo.findDueForDispatch(any(), anyInt(), any())).thenReturn(List.of());

        dispatcher.dispatchPending();

        verify(notificationService, never()).deliver(any());
    }
}
