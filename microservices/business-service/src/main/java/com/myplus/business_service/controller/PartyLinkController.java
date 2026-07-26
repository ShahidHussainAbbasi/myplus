package com.myplus.business_service.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.business_service.service.PartyBridgeService;

import lombok.RequiredArgsConstructor;

/**
 * One-time admin backfill of the cross-module contact view's role index (P4). Customers/vendors bridged BEFORE P4 carry
 * a {@code party_id} already, so the bridge's skip-guard means they never bridge again and would never get a role link.
 * Batched by id cursor and idempotent — call until {@code remaining} is 0. Owner/admin-gated; deliberately not on any
 * automatic path (it walks the whole table).
 */
@RestController
@RequestMapping("/party-links")
@RequiredArgsConstructor
public class PartyLinkController {

    private final PartyBridgeService bridge;

    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam(defaultValue = "200") int limit,
                                        @RequestParam(required = false) Long afterCustomerId,
                                        @RequestParam(required = false) Long afterVenderId) {
        return bridge.backfillLinks(limit, afterCustomerId, afterVenderId);
    }
}
