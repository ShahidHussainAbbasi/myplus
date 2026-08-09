package com.myplus.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.myplus.common.settings.SettingsService;
import com.myplus.education.entity.NotifyOutbox;
import com.myplus.education.repository.NotifyOutboxRepository;
import com.myplus.education.service.EduNotifyService.Outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Slice 3.5's notify gate, written 2026-08-09 — after slice 105's Cypress gate found that
 * {@code sendAlerts} had queued NOTHING since 3.5 shipped.
 *
 * <h3>The bug this class exists to prevent recurring</h3>
 *
 * The "ungated" overload passes a {@code null} setting key, meaning "this caller has no feature switch".
 * {@code enabled(null)} resolved that to {@code settingsService.getBool(null)}, an unknown key returns null,
 * {@code "true".equalsIgnoreCase(null)} is false — and nothing threw, so the deliberate fail-ON catch never
 * fired either. Every recipient came back DISABLED.
 *
 * <p>It survived 3.5's gate, N1's gate and a six-spec regression list because the response stayed
 * {@code SUCCESS} and merely said <i>"Queued for 0 of 40 recipient(s)"</i> — which reads like an empty
 * audience rather than a broken feature. Both cases below assert the COUNT, which is the assertion that was
 * missing everywhere.
 */
@ExtendWith(MockitoExtension.class)
class EduNotifyServiceTest {

    @Mock private NotifyOutboxRepository repo;
    @Mock private ApplicationEventPublisher events;
    @Mock private com.myplus.common.outbox.OutboxRelay relay;
    @Mock private SettingsService settingsService;
    @InjectMocks private EduNotifyService service;

    private void repoAssignsIds() {
        when(repo.save(any(NotifyOutbox.class))).thenAnswer(i -> {
            NotifyOutbox o = i.getArgument(0);
            o.setId(1L);
            return o;
        });
    }

    // ── the ungated path (sendAlerts) ───────────────────────────────────────────────────────────────

    @Test
    void an_ungated_broadcast_queues_EVERY_recipient() {
        // THE regression. A null setting key means "no switch to consult", never "switched off".
        repoAssignsIds();

        int queued = service.queueAll("ALERT", "Closure", "School closed Friday.",
                List.of("a@x.com", "b@x.com", "c@x.com"));

        assertThat(queued).isEqualTo(3);
        verify(repo, times(3)).save(any(NotifyOutbox.class));
        // No settings lookup at all — there is no key to look up, and asking for one is what broke it.
        verify(settingsService, never()).getBool(any());
    }

    @Test
    void an_ungated_single_notice_is_queued_not_disabled() {
        repoAssignsIds();

        Outcome outcome = service.queue("ALERT", null,
                new NotifyMessage("a@x.com", "Closure", "Body"));

        assertThat(outcome).isEqualTo(Outcome.QUEUED);
    }

    @Test
    void the_outbox_row_carries_the_event_type_so_the_relay_can_attribute_it() {
        // Slice 105 reads eventType off this row to name the delivery's source, and organizationId off it
        // because the relay thread has no security context. If either stopped being stamped here, the
        // delivery record would go back to being unattributable.
        repoAssignsIds();

        service.queueAll("ALERT", "Closure", "Body", List.of("a@x.com"));

        ArgumentCaptor<NotifyOutbox> row = ArgumentCaptor.forClass(NotifyOutbox.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("ALERT");
        assertThat(row.getValue().getRecipientEmail()).isEqualTo("a@x.com");
        assertThat(row.getValue().getStatus()).isEqualTo("PENDING");
    }

    // ── the gated path still gates (3.5 must not regress the other way) ─────────────────────────────

    @Test
    void a_gated_broadcast_with_the_switch_OFF_queues_nothing() {
        when(settingsService.getBool("edu.notify.notices")).thenReturn(false);

        int queued = service.queueAll("NOTICE", "edu.notify.notices", "T", "B",
                List.of("a@x.com", "b@x.com"));

        // The fix must not turn every switch into a no-op — that would be the same defect mirrored.
        assertThat(queued).isZero();
        verify(repo, never()).save(any(NotifyOutbox.class));
    }

    @Test
    void a_gated_broadcast_with_the_switch_ON_queues_normally() {
        when(settingsService.getBool("edu.notify.notices")).thenReturn(true);
        repoAssignsIds();

        assertThat(service.queueAll("NOTICE", "edu.notify.notices", "T", "B",
                List.of("a@x.com", "b@x.com"))).isEqualTo(2);
    }

    @Test
    void an_unreadable_setting_fails_ON_rather_than_silently_dropping_the_notice() {
        // Standard C3, and deliberately the opposite default to edu.portal.enabled: an extra email is
        // noise, a missing one is a teacher who does not know they are covering a class.
        when(settingsService.getBool("edu.notify.cover")).thenThrow(new RuntimeException("settings down"));
        repoAssignsIds();

        assertThat(service.queue("COVER", "edu.notify.cover",
                new NotifyMessage("a@x.com", "S", "B"))).isEqualTo(Outcome.QUEUED);
    }

    // ── addresses ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_unusable_address_is_skipped_without_failing_the_broadcast() {
        // D-7: students largely have no address, and the notice is readable in the portal regardless. One
        // bad entry must not cost the other families their notice.
        repoAssignsIds();

        int queued = service.queueAll("ALERT", "S", "B",
                java.util.Arrays.asList("a@x.com", null, "", "   ", "not-an-email", "b@x.com"));

        assertThat(queued).isEqualTo(2);
    }

    @Test
    void an_empty_audience_queues_nothing_and_says_so() {
        assertThat(service.queueAll("ALERT", "S", "B", List.of())).isZero();
        verify(repo, never()).save(any(NotifyOutbox.class));
    }
}
