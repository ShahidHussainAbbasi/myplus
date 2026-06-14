package com.myplus.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@myplus.com}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:8765}")
    private String baseUrl;

    // Landing page for the password-reset link. Points at the monolith UI page that renders the
    // new-password form and posts back through {@code /user/savePassword} -> auth-service reset.
    @Value("${app.reset-password-url:http://localhost:8080/user/changePassword}")
    private String resetPasswordUrl;

    @Async
    public void sendVerificationEmail(String to, String token) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject("MyPlus - Verify Your Email");
            message.setText("Please verify your email by clicking the link:\n\n"
                    + baseUrl + "/api/auth/verify-email?token=" + token
                    + "\n\nThe link expires in 24 hours.");
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("Failed to send verification email to {}: {}", to, ex.getMessage());
        }
    }

    @Async
    public void sendPasswordResetEmail(String to, String token) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject("MyPlus - Password Reset");
            message.setText("To reset your password, click the link below:\n\n"
                    + resetPasswordUrl + "?token=" + token
                    + "\n\n(Or paste this token into the reset form: " + token + ")"
                    + "\n\nThe token expires in 1 hour. If you did not request this, ignore this email.");
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("Failed to send password reset email to {}: {}", to, ex.getMessage());
        }
    }
}
