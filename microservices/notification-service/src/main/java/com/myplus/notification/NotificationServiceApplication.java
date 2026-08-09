package com.myplus.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notification-service (slice 33, Phase 8) — the single platform service that sends notifications
 * (email now; SMS/push later). Consolidates the email senders previously duplicated in auth/education/
 * campaign/monolith. Stateless; every domain calls it instead of re-implementing SMTP.
 */
// Slice 105: WITHOUT THIS THE RETRY RELAY NEVER RUNS and every failed delivery stays PENDING forever —
// silently, because nothing throws. Education shipped exactly that bug in slice 0.1 and had to find it in
// production-shaped testing; it is written down here so this service does not repeat it.
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
