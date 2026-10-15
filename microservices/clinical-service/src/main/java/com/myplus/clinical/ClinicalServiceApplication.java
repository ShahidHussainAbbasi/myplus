package com.myplus.clinical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * clinical-service — the clinic (HMS Phase 1). Owns the clinical identity of a patient (MRN, demographics) and,
 * in later slices, the consultation. The PERSON lives in party-service and the pharmacy CUSTOMER in
 * business-service; a patient row links to both. Design: microservices/docs/hms-phase1-design.md.
 *
 * <p>{@code @EnableScheduling} drives the audit outbox retry.
 */
@SpringBootApplication
@EnableScheduling
public class ClinicalServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClinicalServiceApplication.class, args);
    }
}
