package com.myplus.catalog;

import org.modelmapper.ModelMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * catalog-service (slice 33, Phase 5) — owns product master data (Product/Category). Stock quantity state
 * lives in inventory-service ({@code StockLevel}); this service answers "what is this product".
 */
/*
 * E5 — @EnableScheduling, and it is load-bearing.
 *
 * catalog-service gained an audit outbox, and AuditEmitter's @Scheduled relay is what re-drives anything
 * that did not reach audit-service. Without this the annotation would be present, reviewed, and INERT:
 * rows would sit PENDING for ever and the only symptom would be a trail with gaps nobody could explain.
 * auth-service hit exactly this in E4 and it cost a diagnostic round.
 */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class CatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }
}
