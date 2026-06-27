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

    /** Returns {sent, failed, recipients, errors}. */
    public Map<String, Object> send(String subject, String body, Collection<String> recipients) {
        Set<String> to = new LinkedHashSet<>();
        if (recipients != null) {
            for (String r : recipients) {
                if (r != null && r.contains("@")) to.add(r.trim());
            }
        }
        if (adminRecipientsCsv != null) {
            for (String a : adminRecipientsCsv.split(",")) {
                if (a != null && a.contains("@")) to.add(a.trim());
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
