package com.myplus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 105 — pure Mockito, so it always runs on {@code mvn test} (Testcontainers is skipped on the
 * developer's local stack, and these rules are too important to only be covered there).
 */
@ExtendWith(MockitoExtension.class)
class DeliveryRecorderTest {

    @Mock private NotificationBroadcastRepository broadcastRepo;
    @Mock private NotificationDeliveryRepository deliveryRepo;
    @InjectMocks private DeliveryRecorder recorder;

    private final EmailRequest req = new EmailRequest(
            List.of("a@test.com"), List.of("admin@test.com"), null, "Closure", "School closed Friday");

    @Test
    void writes_one_delivery_row_per_recipient() {
        when(broadcastRepo.save(any())).thenAnswer(i -> {
            NotificationBroadcast b = i.getArgument(0);
            b.setId(1L);
            return b;
        });

        NotificationBroadcast b = recorder.record(req, "EDU-NOTICE", 3L, "notice-1");

        assertThat(b).isNotNull();
        assertThat(b.getTotalRecipients()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationDelivery>> rows = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepo).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue()).allMatch(d -> d.getStatus() == DeliveryStatus.PENDING
                && d.getAttempts() == 0 && d.getOrganizationId().equals(3L));
    }

    @Test
    void a_repeated_dedupe_key_returns_the_original_and_sends_nothing_twice() {
        NotificationBroadcast original = NotificationBroadcast.builder().id(5L).build();
        when(broadcastRepo.findByDedupeKey("notice-1")).thenReturn(Optional.of(original));

        NotificationBroadcast b = recorder.record(req, "EDU-NOTICE", 3L, "notice-1");

        // THE point of the key: education's relay re-POSTs on a timeout, so this happens in normal running.
        // Without it, 300 families get the closure notice twice.
        assertThat(b).isSameAs(original);
        verify(broadcastRepo, never()).save(any());
        verify(deliveryRepo, never()).saveAll(any());
    }

    @Test
    void a_person_in_both_to_and_cc_is_one_delivery() {
        when(broadcastRepo.save(any())).thenAnswer(i -> {
            NotificationBroadcast b = i.getArgument(0);
            b.setId(1L);
            return b;
        });

        NotificationBroadcast b = recorder.record(new EmailRequest(
                List.of("head@test.com"), List.of("head@test.com"), null, "S", "B"), "ALERT", 3L, null);

        assertThat(b.getTotalRecipients()).isEqualTo(1);
    }

    @Test
    void unusable_addresses_are_not_queued_for_delivery() {
        when(broadcastRepo.save(any())).thenAnswer(i -> {
            NotificationBroadcast b = i.getArgument(0);
            b.setId(1L);
            return b;
        });

        NotificationBroadcast b = recorder.record(new EmailRequest(
                List.of("a@test.com", "", "  ", "not-an-email"), null, null, "S", "B"), "ALERT", 3L, null);

        // Otherwise the dispatcher retries a blank string five times before giving up on it.
        assertThat(b.getTotalRecipients()).isEqualTo(1);
    }

    @Test
    void returns_null_rather_than_throwing_when_the_record_cannot_be_written() {
        when(broadcastRepo.save(any())).thenThrow(new RuntimeException("db down"));

        // Contract: recording must never break sending. The caller treats null as "unrecorded but still
        // deliverable" and sends anyway.
        assertThat(recorder.record(req, "ALERT", 3L, null)).isNull();
    }

    @Test
    void a_lost_race_on_the_unique_key_yields_the_winners_broadcast() {
        when(broadcastRepo.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup key"));
        NotificationBroadcast winner = NotificationBroadcast.builder().id(8L).build();
        when(broadcastRepo.findByDedupeKey("notice-1")).thenReturn(Optional.empty(), Optional.of(winner));

        // Two threads, same key: the loser must adopt the winner's broadcast, not fail the send.
        assertThat(recorder.record(req, "ALERT", 3L, "notice-1")).isSameAs(winner);
    }

    @Test
    void success_marks_sent_and_clears_the_previous_error() {
        NotificationDelivery d = NotificationDelivery.builder()
                .id(1L).attempts(2).lastError("earlier timeout").status(DeliveryStatus.PENDING).build();

        recorder.settleOne(d, true, null);

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(d.getAttempts()).isEqualTo(3);
        assertThat(d.getSentAt()).isNotNull();
        assertThat(d.getLastError()).isNull();
        verify(deliveryRepo).save(d);
    }

    @Test
    void failure_stays_pending_so_the_dispatcher_will_retry_it() {
        NotificationDelivery d = NotificationDelivery.builder()
                .id(1L).attempts(0).status(DeliveryStatus.PENDING).build();

        recorder.settleOne(d, false, "connection reset");

        // Deliberate: only the attempt cap moves a row to FAILED, and that decision lives in the
        // dispatcher. If this marked FAILED, one transient blip would permanently drop the message.
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(d.getAttempts()).isEqualTo(1);
        assertThat(d.getLastError()).isEqualTo("connection reset");
    }
}
