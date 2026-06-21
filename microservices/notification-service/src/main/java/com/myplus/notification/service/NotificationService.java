package com.myplus.notification.service;

import com.myplus.common.notify.EmailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends notifications (slice 33, Phase 8). The one place SMTP lives now; replaces the per-service EmailServices.
 * Best-effort: a send failure is logged and reported (returns false), never thrown, so a caller's main
 * operation isn't coupled to mail delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@myplus.com}")
    private String fromAddress;

    public boolean sendEmail(EmailRequest req) {
        if (req == null || req.getTo() == null || req.getTo().isEmpty()) {
            throw new IllegalArgumentException("email 'to' is required");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(req.getTo().toArray(new String[0]));
            if (req.getCc() != null && !req.getCc().isEmpty()) {
                message.setCc(req.getCc().toArray(new String[0]));
            }
            if (req.getReplyTo() != null && !req.getReplyTo().isBlank()) {
                message.setReplyTo(req.getReplyTo());
            }
            message.setSubject(req.getSubject());
            message.setText(req.getBody());
            mailSender.send(message);
            log.info("Email sent to {} (cc {}) subject='{}'", req.getTo(), req.getCc(), req.getSubject());
            return true;
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", req.getTo(), ex.getMessage(), ex);
            return false;
        }
    }
}
