package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.myplus.common.notify.NotificationClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        when(notificationClient.sendEmail(any(), any(), any(), any())).thenReturn(true);

        Map<String, Object> r = service.send("Subject", "Body", List.of("a@x.com", "b@x.com"));

        assertThat(r.get("recipients")).isEqualTo(3);   // a, b, admin
        assertThat(r.get("sent")).isEqualTo(3);
        assertThat(r.get("failed")).isEqualTo(0);
        // slice 105 — the broadcast path names itself, so the record is attributable
        verify(notificationClient, times(3)).sendEmail(any(), eq("EDU-ALERT"), any(), any());
    }

    @Test
    void each_recipient_gets_a_DISTINCT_dedupe_key() {
        // Slice 105, and the case that stops a silent data-loss bug: one alert to three guardians is three
        // separate deliveries. If the key were derived from the message alone, all three would collide on
        // uk_broadcast_dedupe and only the FIRST would ever be recorded — the other two families would
        // vanish from the record while appearing to have been sent.
        when(notificationClient.sendEmail(any(), any(), any(), any())).thenReturn(true);

        service.send("Subject", "Body", List.of("a@x.com", "b@x.com"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(notificationClient, times(3))
                .sendEmail(any(), any(), any(), keys.capture());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void the_same_message_to_the_same_person_reuses_its_key() {
        // The other half of the contract. The key is derived from the content, not randomised — a random
        // key would differ on the retry and so would deduplicate nothing, which is its only job.
        when(notificationClient.sendEmail(any(), any(), any(), any())).thenReturn(true);

        service.send("Subject", "Body", List.of("a@x.com"));
        service.send("Subject", "Body", List.of("a@x.com"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(notificationClient, times(4)).sendEmail(any(), any(), any(), keys.capture());
        // Two passes over the same two addresses => two distinct keys, each seen twice.
        assertThat(keys.getAllValues()).hasSize(4);
        assertThat(java.util.Set.copyOf(keys.getAllValues())).hasSize(2);
    }

    @Test
    void counts_failures_when_the_client_reports_not_sent() {
        when(notificationClient.sendEmail(any(), any(), any(), any())).thenReturn(false);

        Map<String, Object> r = service.send("S", "B", List.of("a@x.com"));

        assertThat(r.get("failed")).isEqualTo(2);       // a + admin both reported failed
        assertThat(r.get("sent")).isEqualTo(0);
    }
}
