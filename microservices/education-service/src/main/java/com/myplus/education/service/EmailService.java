package com.myplus.education.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends alert emails via the configured SMTP (slice 16). Best-effort per recipient so one bad address
 * doesn't fail the batch. The configured admin recipients are ALWAYS added so every send is observable.
 */
@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${education.alerts.from:maxtheservice@gmail.com}")
    private String from;

    @Value("${education.alerts.admin-recipients:}")
    private String adminRecipientsCsv;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Returns {sent, failed, recipients, errors}. */
    public Map<String, Object> send(String subject, String body, Collection<String> recipients) {
        Set<String> to = new LinkedHashSet<>();
        if (recipients != null) {
            for (String r : recipients) {
                if (r != null && r.contains("@")) to.add(r.trim());
            }
        }
        for (String a : adminRecipientsCsv.split(",")) {
            if (a != null && a.contains("@")) to.add(a.trim());
        }

        int sent = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (String r : to) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(from);
                msg.setTo(r);
                msg.setSubject(subject == null ? "(no subject)" : subject);
                msg.setText(body == null ? "" : body);
                mailSender.send(msg);
                sent++;
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
