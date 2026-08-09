package com.myplus.education.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.myplus.common.notify.EmailRequest;
import com.myplus.common.security.CurrentUser;
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
        return sendTo(subject, body, to, "EDU-ALERT");
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
        return sendTo(subject, body, recipients, "EDU-NOTIFY");
    }

    /**
     * As above, but naming WHICH education flow asked — slice 105.
     *
     * <p>The tenant is resolved HERE, once, rather than being threaded through every caller: attribution is
     * a property of the request, and every path into this class already runs inside one. That is also what
     * makes the record readable — the read endpoints are tenant-scoped, so a send with no orgId records a
     * row nobody can retrieve.
     */
    public Map<String, Object> sendTo(String subject, String body, Collection<String> recipients, String source) {
        return sendTo(subject, body, recipients, source, CurrentUser.organizationId());
    }

    /**
     * As above, but with the tenant passed IN — slice 105.
     *
     * <p><b>Why this overload has to exist.</b> The outbox relay sends on a SCHEDULER thread, which has no
     * security context, so {@code CurrentUser.organizationId()} there is null. Recording a tenant-less row
     * would not fail loudly — it would write a delivery nobody can ever read, because the read endpoints
     * are tenant-scoped. Every relayed message would silently drop out of the record while appearing to
     * have been sent, which is precisely the class of failure this slice exists to end.
     *
     * <p>The relay therefore passes the tenant it stored on the outbox row when the request thread queued
     * it. Request-thread callers keep using the overload above and resolve it from the context.
     */
    public Map<String, Object> sendTo(String subject, String body, Collection<String> recipients,
                                      String source, Long orgId) {
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
                        .build(), source, orgId, dedupeKey(source, orgId, subject, body, r));
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

    /**
     * A stable idempotency key for one message to one person.
     *
     * <p>Derived from the CONTENT (source + tenant + subject + body + recipient) rather than a random value,
     * because a random key would be different on the retry and so would deduplicate nothing — which is the
     * one job it has. Two genuinely different notices differ in subject or body and so get different keys;
     * a re-POST of the same notice after a timeout gets the same key and is answered, not re-sent.
     *
     * <p>Its limit, stated plainly: a deliberate re-send of an IDENTICAL message to the same person is
     * treated as a duplicate. For a cover notice or a closure alert that is the desired behaviour; a flow
     * that genuinely needs to repeat itself must vary the body or pass its own key.
     */
    private static String dedupeKey(String source, Long orgId, String subject, String body, String recipient) {
        String material = source + "|" + orgId + "|" + subject + "|" + body + "|" + recipient;
        return source + "-" + Integer.toHexString(material.hashCode())
                + "-" + Integer.toHexString(recipient.hashCode());
    }
}
