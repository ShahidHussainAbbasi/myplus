package com.myplus.auth.service;

import com.myplus.common.notify.EmailRequest;
import com.myplus.common.notify.NotificationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Composes auth-specific emails (verification, password reset) and delegates delivery to notification-service
 * via {@link NotificationClient} (slice 33, Phase 8) — the SMTP/JavaMailSender lives there now. Still @Async
 * so the calling auth flow isn't blocked on delivery; best-effort (the client logs, never throws here).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final NotificationClient notificationClient;

    @Value("${app.base-url:http://localhost:8765}")
    private String baseUrl;

    // Landing page for the password-reset link. Points at the monolith UI page that renders the
    // new-password form and posts back through {@code /user/savePassword} -> auth-service reset.
    @Value("${app.reset-password-url:http://localhost:8080/user/changePassword}")
    private String resetPasswordUrl;

    @Async
    public void sendVerificationEmail(String to, String token) {
        try {
            notificationClient.sendEmail(EmailRequest.builder()
                    .to(List.of(to))
                    .subject("MyPlus - Verify Your Email")
                    .body("Please verify your email by clicking the link:\n\n"
                            + baseUrl + "/api/auth/verify-email?token=" + token
                            + "\n\nThe link expires in 24 hours.")
                    .build());
            log.info("Verification email queued for {}", to);
        } catch (Exception ex) {
            log.error("Failed to queue verification email to {} (account stays disabled until verified): {}",
                    to, ex.getMessage(), ex);
        }
    }

    @Async
    public void sendPasswordResetEmail(String to, String token) {
        try {
            notificationClient.sendEmail(EmailRequest.builder()
                    .to(List.of(to))
                    .subject("MyPlus - Password Reset")
                    .body("To reset your password, click the link below:\n\n"
                            + resetPasswordUrl + "?token=" + token
                            + "\n\n(Or paste this token into the reset form: " + token + ")"
                            + "\n\nThe token expires in 1 hour. If you did not request this, ignore this email.")
                    .build());
            log.info("Password reset email queued for {}", to);
        } catch (Exception ex) {
            log.error("Failed to queue password reset email to {}: {}", to, ex.getMessage(), ex);
        }
    }
}
