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

/**
 * Slice 33, Phase 8 — pure Mockito (always runs). Builds + sends the message; reports false on SMTP failure
 * (never throws); rejects a missing recipient.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private JavaMailSender mailSender;
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

    @Test
    void rejects_missing_recipient() {
        assertThatThrownBy(() -> service.sendEmail(new EmailRequest(null, null, null, "Hi", "Body")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
