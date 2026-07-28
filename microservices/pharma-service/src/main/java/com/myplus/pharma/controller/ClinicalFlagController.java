package com.myplus.pharma.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.pharma.service.ClinicalFlagBackfillService;

import lombok.RequiredArgsConstructor;

/**
 * One-time admin backfill of the pharmacy clinical flags into the catalog product master (review B1, design D6).
 * Cross-database, so it cannot be a Flyway script. Batched by id cursor, idempotent — call until {@code remaining}
 * is 0. Owner/admin-gated; not on any automatic path.
 */
@RestController
@RequestMapping("/clinical-flags")
@RequiredArgsConstructor
public class ClinicalFlagController {

    private final ClinicalFlagBackfillService backfillService;

    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam(defaultValue = "200") int limit,
                                        @RequestParam(required = false) Long afterId) {
        return backfillService.backfill(limit, afterId);
    }
}
