package com.myplus.business_service.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Repair-then-migrate on startup: {@code repair()} clears any failed / half-applied entries from
 * flyway_schema_history (e.g. a dev migration that errored mid-run), then {@code migrate()} applies pending
 * migrations. Safe here because every business-service migration is guarded/idempotent (information_schema-checked),
 * so re-running is a no-op — this just stops a failed dev migration from blocking startup and needing a manual
 * DELETE from the history table.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            flyway.repair();   // drop failed markers + realign checksums
            flyway.migrate();  // apply pending migrations
        };
    }
}
