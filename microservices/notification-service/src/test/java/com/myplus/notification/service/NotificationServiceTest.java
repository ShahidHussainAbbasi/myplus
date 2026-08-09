package com.myplus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.myplus.common.notify.EmailRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import com.myplus.notification.entity.NotificationBroadcast;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Slice 33, Phase 8 — pure Mockito (always runs). Builds + sends the message; reports false on SMTP failure
 * (never throws); rejects a missing recipient.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private JavaMailSender mailSender;
    /**
     * Slice 105. Required because @InjectMocks builds through the constructor: without it the provider is
     * null and every send NPEs. Left returning null by default, which is the "datastore unavailable" path —
     * so the three original tests below still prove sending works with no recording at all.
     */
    @Mock private ObjectProvider<DeliveryRecorder> recorderProvider;
    @InjectMocks private NotificationService service;

    @Test
    void sends_email_with_recipients_cc_subject_and_body() {
        boolean sent = service.sendEmail(new EmailRequest(
                List.of("a@test.com"), List.of("admin@test.com"), null, "Hi", "Body"));

        assertThat(sent).isTrue();
        ArgumentCaptor<SimpleMailMessage> msg = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(msg.capture());
        assertThat(msg.getValue().getTo()).containsExactly("a@test.com");
        assertThat(msg.getValue().getCc()).containsExactly("admin@test.com");
        assertThat(msg.getValue().getSubject()).isEqualTo("Hi");
        assertThat(msg.getValue().getText()).isEqualTo("Body");
    }

    @Test
    void returns_false_on_smtp_failure_instead_of_throwing() {
        doThrow(new org.springframework.mail.MailSendException("smtp down")).when(mailSender).send((SimpleMailMessage) org.mockito.ArgumentMatchers.any());

        boolean sent = service.sendEmail(new EmailRequest(List.of("a@test.com"), null, null, "Hi", "Body"));

        assertThat(sent).isFalse();
    }

    // ── Slice 105: the send path now leaves a record ────────────────────────────────────────────────

    @Test
    void records_the_broadcast_then_marks_it_sent() {
        DeliveryRecorder recorder = org.mockito.Mockito.mock(DeliveryRecorder.class);
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        NotificationBroadcast b = NotificationBroadcast.builder().id(7L).build();
        when(recorder.record(any(), eq("EDU-NOTICE"), eq(3L), eq("notice-42"))).thenReturn(b);

        boolean sent = service.sendEmail(
                new EmailRequest(List.of("a@test.com"), null, null, "Hi", "Body"),
                "EDU-NOTICE", 3L, "notice-42");

        assertThat(sent).isTrue();
        verify(recorder).settle(7L, true, null);
    }

    @Test
    void records_the_failure_reason_when_smtp_rejects_it() {
        DeliveryRecorder recorder = org.mockito.Mockito.mock(DeliveryRecorder.class);
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        when(recorder.record(any(), any(), any(), any()))
                .thenReturn(NotificationBroadcast.builder().id(9L).build());
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send((SimpleMailMessage) any());

        boolean sent = service.sendEmail(
                new EmailRequest(List.of("a@test.com"), null, null, "Hi", "Body"), "ALERT", 3L, null);

        assertThat(sent).isFalse();
        // The reason is captured, and the row stays PENDING so the dispatcher retries. Before slice 105
        // this failure existed only as a log line.
        verify(recorder).settle(eq(9L), eq(false), org.mockito.ArgumentMatchers.contains("smtp down"));
    }

    @Test
    void still_sends_when_the_datastore_is_unavailable() {
        // Recording is best-effort by design: a closure notice arriving matters more than its audit row.
        when(recorderProvider.getIfAvailable()).thenReturn(null);

        boolean sent = service.sendEmail(
                new EmailRequest(List.of("a@test.com"), null, null, "Hi", "Body"), "ALERT", 3L, "k");

        assertThat(sent).isTrue();
        verify(mailSender).send((SimpleMailMessage) any());
    }

    @Test
    void rejects_missing_recipient_before_recording_anything() {
        // Validation stays FIRST: a malformed request must not leave a broadcast row behind.
        DeliveryRecorder recorder = org.mockito.Mockito.mock(DeliveryRecorder.class);
        assertThatThrownBy(() -> service.sendEmail(
                new EmailRequest(null, null, null, "Hi", "Body"), "ALERT", 3L, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recorder, never()).record(any(), any(), any(), any());
    }

    @Test
    void rejects_missing_recipient() {
        assertThatThrownBy(() -> service.sendEmail(new EmailRequest(null, null, null, "Hi", "Body")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
