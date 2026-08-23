package com.myplus.business_service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling, with an off switch — default ON, so production and dev are unchanged.
 *
 * <h3>Why this moved off the application class</h3>
 * business-service runs relays on a timer: {@code GlOutboxService.flushPending} re-drives undelivered GL
 * events, {@code SagaRecoveryRelay.reconfirmPending} re-confirms pending saga sales, {@code AuditService} and
 * {@code ReminderScanner} likewise. Under {@code mvn test} they fire against the Testcontainers database —
 * every one of them at least once, because a {@code fixedDelay} with no {@code initialDelay} runs immediately
 * at startup, so tuning the delay properties would NOT have prevented it.
 *
 * <p>Two costs, and the second is the one that matters:
 * <ol>
 *   <li>When the container stops, an in-flight relay blocks on Hikari's 30-second {@code connectionTimeout}
 *       and holds shutdown — the {@code "Surefire is going to kill self fork JVM. The exit has elapsed 30
 *       seconds after System.exit(0)"} that appeared after every Testcontainers class, costing ~30s each.</li>
 *   <li><b>Background threads writing to the database mid-test.</b> An outbox relay that flushes rows, or a
 *       recovery relay that re-confirms a sale, while a test is asserting on that same data is a source of
 *       intermittent failures that look like product bugs. Nothing has been traced to it yet; that is not the
 *       same as it being safe.</li>
 * </ol>
 *
 * <p>The switch is set for the whole reactor in the parent pom's surefire {@code systemPropertyVariables},
 * beside {@code api.version} — one place, and a test class added later cannot forget it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
