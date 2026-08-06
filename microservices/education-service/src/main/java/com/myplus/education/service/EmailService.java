package com.myplus.education.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.myplus.common.notify.EmailRequest;
import com.myplus.common.notify.NotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends alert emails (slice 16) via notification-service (slice 33, Phase 8 — SMTP lives there now).
 * Best-effort per recipient so one bad address doesn't fail the batch. The configured admin recipients are
 * ALWAYS added so every send is observable.
 */
@Service
public class EmailService {

    private final NotificationClient notificationClient;

    @Value("${education.alerts.admin-recipients:}")
    private String adminRecipientsCsv;

    public EmailService(NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    /**
     * Broadcast send — the named recipients <b>plus</b> the configured admin recipients.
     *
     * <p>Unchanged behaviour, used by the alerts screen: when a school emails 300 guardians, the office
     * wants a copy of what went out.
     *
     * <p>Returns {sent, failed, recipients, errors}.
     */
    public Map<String, Object> send(String subject, String body, Collection<String> recipients) {
        Set<String> to = new LinkedHashSet<>();
        if (recipients != null) to.addAll(recipients);
        if (adminRecipientsCsv != null) to.addAll(List.of(adminRecipientsCsv.split(",")));
        return sendTo(subject, body, to);
    }

    /**
     * Targeted send — <b>exactly</b> the named recipients, with no admin CC (slice N1, design D2).
     *
     * <p>The admin copy is right for a broadcast and wrong for a one-person operational message: CCing the
     * office on every cover assignment in the school is how people learn to filter the sender.
     *
     * <p>This is the single sender — {@link #send} delegates here — so there is one retry-and-count loop and
     * one place where an unusable address is skipped.
     *
     * <p>Returns {sent, failed, recipients, errors}.
     */
    public Map<String, Object> sendTo(String subject, String body, Collection<String> recipients) {
        Set<String> to = new LinkedHashSet<>();
        if (recipients != null) {
            for (String r : recipients) {
                if (r != null && r.contains("@")) to.add(r.trim());
            }
        }

        int sent = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (String r : to) {
            try {
                Boolean ok = notificationClient.sendEmail(EmailRequest.builder()
                        .to(List.of(r))
                        .subject(subject == null ? "(no subject)" : subject)
                        .body(body == null ? "" : body)
                        .build());
                if (Boolean.TRUE.equals(ok)) {
                    sent++;
                } else {
                    failed++;
                    errors.add(r + ": send failed");
                }
            } catch (Exception e) {
                failed++;
                errors.add(r + ": " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", sent);
        out.put("failed", failed);
        out.put("recipients", to.size());
        out.put("errors", errors);
        return out;
    }
}
