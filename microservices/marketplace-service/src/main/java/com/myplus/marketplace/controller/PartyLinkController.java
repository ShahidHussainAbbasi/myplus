package com.myplus.marketplace.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.marketplace.service.PartyBridgeService;

import lombok.RequiredArgsConstructor;

/**
 * One-time admin backfill of the contact view's role index for storefront shoppers bridged before the index
 * existed (they carry a party_id already, so the bridge's skip-guard means they never bridge again). Batched by id
 * cursor, idempotent — call until {@code remaining} is 0. Owner/admin-gated; not on any automatic path.
 */
@RestController
@RequestMapping("/party-links")
@RequiredArgsConstructor
public class PartyLinkController {

    private final PartyBridgeService bridge;

    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam(defaultValue = "200") int limit,
                                        @RequestParam(required = false) Long afterId) {
        return bridge.backfillLinks(limit, afterId);
    }
}
