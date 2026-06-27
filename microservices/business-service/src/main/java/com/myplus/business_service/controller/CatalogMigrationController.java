package com.myplus.business_service.controller;

import com.myplus.business_service.dto.CatalogMigrationResult;
import com.myplus.business_service.dto.StockMigrationResult;
import com.myplus.business_service.service.CatalogMigrationService;
import com.myplus.business_service.service.StockMigrationService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin trigger for the item→product migration (slice 33, U2). Migrates the caller's organization. Role-gated
 * (ADD_ITEM) and idempotent — safe to re-run; only not-yet-migrated items are sent to catalog.
 */
// Mapped at /admin/* (root-relative): the gateway route /api/business/** uses StripPrefix=2, so a call to
// /api/business/admin/migrate-catalog reaches business-service as /admin/migrate-catalog (slice 33, U2).
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class CatalogMigrationController {

    private final CatalogMigrationService catalogMigrationService;
    private final StockMigrationService stockMigrationService;
    private final RequestUtil requestUtil;

    @PostMapping("/migrate-catalog")
    @PreAuthorize("hasAuthority('ADD_ITEM')")
    public ResponseEntity<CatalogMigrationResult> migrate() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        return ResponseEntity.ok(catalogMigrationService.migrate(user.getOrganizationId(), user.getUserId()));
    }

    /** Seed migrated products' opening stock into inventory (run after /migrate-catalog). Idempotent. */
    @PostMapping("/migrate-stock")
    @PreAuthorize("hasAuthority('ADD_ITEM')")
    public ResponseEntity<StockMigrationResult> migrateStock() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        return ResponseEntity.ok(stockMigrationService.migrateStock(user.getOrganizationId(), user.getUserId()));
    }

    /** M3c.1 (slice 76): backfill product_id onto historical Stock-linked sells/purchases (run after /migrate-catalog).
     *  Idempotent; prepares the data so the local Stock FK can be retired (M3c.4). */
    @PostMapping("/backfill-product-ids")
    @PreAuthorize("hasAuthority('ADD_ITEM')")
    public ResponseEntity<CatalogMigrationService.BackfillResult> backfillProductIds() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        return ResponseEntity.ok(catalogMigrationService.backfillProductIds(user.getOrganizationId(), user.getUserId()));
    }
}
