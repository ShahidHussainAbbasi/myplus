package com.myplus.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notification-service (slice 33, Phase 8) — the single platform service that sends notifications
 * (email now; SMS/push later). Consolidates the email senders previously duplicated in auth/education/
 * campaign/monolith. Stateless; every domain calls it instead of re-implementing SMTP.
 */
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
