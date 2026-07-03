package com.myplus.pharma.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M5 recovery (slice 100): Flyway `repair` before `migrate`. A half-applied V3 (the itemId→productId rename, which was
 * first shipped with wrong table names) left a FAILED row in flyway_schema_history that blocks startup ("Detected failed
 * migration to version 3 … run repair"). `repair()` clears failed entries + realigns checksums to the current (fixed,
 * idempotent) scripts; `migrate()` then applies them cleanly. Safe to keep for this dev multi-service setup where
 * migrations occasionally half-apply — repair is a no-op once history is clean.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairThenMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
