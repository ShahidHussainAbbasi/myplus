package com.myplus.business_service.service;

import com.myplus.business_service.dto.CatalogMigrationResult;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.ItemRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.SellRepo;
import com.myplus.business_service.repository.StockRepo;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Item→Product migration (slice 33, U2). Reads the org's business Items, sends the not-yet-migrated ones to
 * catalog-service's bulk import, and records the itemId→productId map. Idempotent / re-runnable: items already
 * in {@link ItemCatalogMap} are skipped. org/user are params (the controller reads the authenticated user).
 */
@Service
@RequiredArgsConstructor
public class CatalogMigrationService {

    private final ItemRepo itemRepo;
    private final ItemCatalogMapRepo mapRepo;
    private final CatalogClient catalogClient;
    private final StockRepo stockRepo;
    private final SellRepo sellRepo;
    private final PurchaseRepo purchaseRepo;

    /** M3c.1 (slice 76): result of stamping product_id onto historical Stock-linked sells/purchases. */
    public record BackfillResult(int sellsBackfilled, int purchasesBackfilled, long sellsRemaining, long purchasesRemaining) {}

    /**
     * M3c.1: backfill product_id onto historical (Stock-linked) sells + purchases via the item→product map, so the
     * local Stock FK can be retired later. Idempotent (only fills NULLs). Run /migrate-catalog first for full coverage;
     * {@code *Remaining} reports rows whose item still isn't mapped (should be 0 after a migrate).
     */
    @Transactional
    public BackfillResult backfillProductIds(Long orgId, Long userId) {
        int sells = sellRepo.backfillProductIds(orgId, userId);
        int purchases = purchaseRepo.backfillProductIds(orgId, userId);
        long sellsRemaining = sellRepo.countWithoutProductId(orgId, userId);
        long purchasesRemaining = purchaseRepo.countWithoutProductId(orgId, userId);
        return new BackfillResult(sells, purchases, sellsRemaining, purchasesRemaining);
    }

    /**
     * M3c (slice 82): deploy-time auto-migrate — map every tenant's not-yet-mapped items to catalog Products. Idempotent;
     * returns the number of tenant groups migrated (0 in the normal case → no catalog call). Each group runs AS that
     * tenant ({@code runAs}) so catalog stamps the right org. Not @Transactional (each {@code migrate} self-commits its
     * saves); the catalog import is an HTTP call we don't want to hold a DB tx across.
     */
    public int migrateAllUnmapped() {
        java.util.List<Object[]> pairs = itemRepo.findUnmappedOrgUser();
        int groups = 0;
        for (Object[] p : pairs) {
            Long org = (Long) p[0];
            Long user = (Long) p[1];
            if (org == null && user == null) continue;
            com.myplus.common.security.GatewayIdentityForwarding.runAs(user != null ? user : 0L, org, () -> migrate(org, user));
            groups++;
        }
        return groups;
    }

    /** M3c (slice 82): all-tenant product_id backfill (after {@link #migrateAllUnmapped} maps new items at deploy time). */
    @Transactional
    public BackfillResult backfillAll() {
        int sells = sellRepo.backfillAllProductIds();
        int purchases = purchaseRepo.backfillAllProductIds();
        return new BackfillResult(sells, purchases, 0L, 0L);
    }

    @Transactional
    public CatalogMigrationResult migrate(Long orgId, Long userId) {
        List<Item> items = itemRepo.findScoped(orgId, userId);
        Set<Long> alreadyMapped = new HashSet<>(mapRepo.findMappedItemIds(orgId));

        List<ProductImportLine> toImport = items.stream()
                .filter(i -> !alreadyMapped.contains(i.getId()))
                .map(this::toLine)
                .toList();

        if (toImport.isEmpty()) {
            return new CatalogMigrationResult(items.size(), 0, alreadyMapped.size());
        }

        List<ProductImportResult> results = catalogClient.importProducts(toImport);
        for (ProductImportResult r : results) {
            mapRepo.save(ItemCatalogMap.builder()
                    .itemId(r.getClientRef()).productId(r.getProductId()).organizationId(orgId)
                    .build());
        }
        return new CatalogMigrationResult(items.size(), results.size(), alreadyMapped.size());
    }

    /**
     * M3.2 (slice 63): ensure a single item is catalog-mapped, importing it on demand if not. Returns its
     * productId (existing or freshly created). Idempotent. Lets the purchase path push EVERY item's stock-in to
     * inventory — even legacy items never run through the bulk migration — so inventory is authoritative.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Long ensureMapped(Long itemId, Long orgId, Long userId) {
        if (itemId == null) return null;
        var existing = mapRepo.findProductIdByItemId(itemId, orgId);
        if (existing.isPresent()) return existing.get();
        Item item = itemRepo.findById(itemId).orElse(null);
        if (item == null) return null;
        List<ProductImportResult> results = catalogClient.importProducts(List.of(toLine(item)));
        if (results.isEmpty()) return null;
        ProductImportResult r = results.get(0);
        mapRepo.save(ItemCatalogMap.builder()
                .itemId(r.getClientRef()).productId(r.getProductId()).organizationId(orgId).build());
        return r.getProductId();
    }

    private ProductImportLine toLine(Item i) {
        return ProductImportLine.builder()
                .clientRef(i.getId())
                .sku(i.getIcode())
                .name(i.getIname())
                .description(i.getIdesc())
                .unit(i.getUnit())
                .manufacturer(i.getCompany() != null ? i.getCompany().getName() : null)
                .categoryName(i.getCategory())
                .sellingPrice(sellRateOf(i))      // carry the legacy sell rate so the saga doesn't sell at 0
                .build();
    }

    /** The item's sell price for the catalog = its (single) Stock row's bsellRate, when set and positive. */
    private BigDecimal sellRateOf(Item i) {
        return stockRepo.findByItemId(i.getId())
                .map(Stock::getBsellRate)
                .filter(r -> r != null && r.signum() > 0)
                .orElse(null);
    }
}
