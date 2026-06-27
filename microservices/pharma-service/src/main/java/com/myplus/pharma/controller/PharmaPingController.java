package com.myplus.pharma.controller;

import com.myplus.common.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Pharma-service liveness/route proof (P0a, slice 41). Reachable at {@code /api/pharma/ping} through the gateway
 * (StripPrefix=2 → service sees {@code /ping}); authenticated, so it also proves gateway identity propagation.
 * Confirms the service is up + composing the mesh before the MedicineProfile work (P0b).
 */
@RestController
public class PharmaPingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", "pharma-service",
                "status", "UP",
                "organizationId", String.valueOf(CurrentUser.organizationId()),
                "time", LocalDateTime.now().toString());
    }
}
