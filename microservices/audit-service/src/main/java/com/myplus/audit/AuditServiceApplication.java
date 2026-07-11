package com.myplus.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * audit-service — the platform's standalone, append-only audit trail. Any service (business/POS, finance, inventory,
 * education, …) emits money/stock/config events here via the shared AuditClient contract, captured reliably through
 * the producer's transactional outbox. Immutable store + org-scoped reads. See docs/finance-audit-log-design.md.
 */
@SpringBootApplication
public class AuditServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
