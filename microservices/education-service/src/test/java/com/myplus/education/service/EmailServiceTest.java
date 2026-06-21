package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.myplus.common.notify.NotificationClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Slice 33, Phase 8 — pure Mockito (always runs). Alerts go out one email per recipient via
 * notification-service, the admin copy is always added, and sent/failed are counted from the client result.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private NotificationClient notificationClient;
    @InjectMocks private EmailService service;

    @BeforeEach
    void adminCopy() {
        ReflectionTestUtils.setField(service, "adminRecipientsCsv", "admin@x.com");
    }

    @Test
    void sends_one_email_per_recipient_plus_admin_and_counts_sent() {
        when(notificationClient.sendEmail(any())).thenReturn(true);

        Map<String, Object> r = service.send("Subject", "Body", List.of("a@x.com", "b@x.com"));

        assertThat(r.get("recipients")).isEqualTo(3);   // a, b, admin
        assertThat(r.get("sent")).isEqualTo(3);
        assertThat(r.get("failed")).isEqualTo(0);
        verify(notificationClient, times(3)).sendEmail(any());
    }

    @Test
    void counts_failures_when_the_client_reports_not_sent() {
        when(notificationClient.sendEmail(any())).thenReturn(false);

        Map<String, Object> r = service.send("S", "B", List.of("a@x.com"));

        assertThat(r.get("failed")).isEqualTo(2);       // a + admin both reported failed
        assertThat(r.get("sent")).isEqualTo(0);
    }
}
