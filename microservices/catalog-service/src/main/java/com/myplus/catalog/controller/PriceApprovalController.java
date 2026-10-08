package com.myplus.catalog.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.catalog.service.PriceApprovalService;
import com.myplus.common.web.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * PR-4 — the owner's queue of prices waiting for approval. Proposing is open to whoever records the purchase (the
 * purchase path calls it with that user's identity); seeing, approving and rejecting are owner/admin only — approving
 * your own bill's price is the control this exists for. Every read and write is scoped to the caller's tenant.
 */
@RestController
@RequestMapping("/api/catalog/price-proposals")
@RequiredArgsConstructor
public class PriceApprovalController {

    private static final String OWNER_OR_ADMIN =
            "hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')";

    private final PriceApprovalService service;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> propose(@RequestParam Long productId,
                                                                    @RequestParam BigDecimal proposedPrice,
                                                                    @RequestParam(required = false) BigDecimal cost,
                                                                    @RequestParam(required = false) String source,
                                                                    @RequestParam(required = false) String reason,
                                                                    @RequestParam(required = false) String detail,
                                                                    @RequestParam(required = false) String ref) {
        Map<String, Object> made = service.propose(productId, proposedPrice, cost, source, reason, detail, ref);
        return ResponseEntity.ok(ApiResponse.success(made, made == null ? "Nothing to approve" : "Sent for approval"));
    }

    @GetMapping
    @PreAuthorize(OWNER_OR_ADMIN)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(service.list(status), "Price changes"));
    }

    @GetMapping("/count")
    @PreAuthorize(OWNER_OR_ADMIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> count() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("pending", service.countPending()), "Pending price changes"));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(OWNER_OR_ADMIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(@PathVariable Long id,
                                                                    @RequestParam(required = false) BigDecimal expectedCurrent) {
        return ResponseEntity.ok(ApiResponse.success(service.approve(id, expectedCurrent), "Price approved"));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(OWNER_OR_ADMIN)
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(@PathVariable Long id,
                                                                   @RequestParam(required = false) String note) {
        return ResponseEntity.ok(ApiResponse.success(service.reject(id, note), "Price change rejected"));
    }
}
