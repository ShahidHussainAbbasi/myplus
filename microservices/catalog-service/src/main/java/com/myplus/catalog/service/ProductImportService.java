package com.myplus.catalog.service;

import com.myplus.commerce.contracts.dto.ProductImportLine;
import com.myplus.commerce.contracts.dto.ProductImportResult;
import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bulk product import for the item→product migration (slice 33, U2). org/user are params (the controller
 * reads CurrentUser) so the logic is unit-testable. Idempotent on (org, sku): an existing product with the
 * same sku is reused rather than duplicated; blank sku falls back to {@code ITEM-<clientRef>}; collisions
 * within the batch/tenant are suffixed. Category is resolved find-or-create by name per tenant.
 *
 * Note: primary idempotency is the caller's itemId→productId map (business ItemCatalogMap); sku-reuse here is
 * a best-effort safety net for the common unique-sku case.
 */
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public List<ProductImportResult> importProducts(List<ProductImportLine> items, Long orgId, Long userId) {
        List<ProductImportResult> results = new ArrayList<>();
        Set<String> usedThisBatch = new HashSet<>();

        for (ProductImportLine dto : items) {
            String baseSku = (dto.getSku() == null || dto.getSku().isBlank())
                    ? "ITEM-" + dto.getClientRef() : dto.getSku().trim();

            // Idempotent reuse: a product already at this sku (e.g. a re-run) is returned, not duplicated.
            var existing = productRepository.findBySkuScoped(baseSku, orgId, userId);
            if (existing.isPresent()) {
                usedThisBatch.add(baseSku);
                results.add(new ProductImportResult(dto.getClientRef(), existing.get().getId()));
                continue;
            }

            // Ensure a unique sku within this batch and the tenant.
            String sku = baseSku;
            int n = 2;
            while (usedThisBatch.contains(sku) || productRepository.existsBySkuScoped(sku, orgId, userId)) {
                sku = baseSku + "-" + (n++);
            }
            usedThisBatch.add(sku);

            // catalog requires a name; fall back to the sku when the source item had none (incomplete data
            // shouldn't drop the item — it must still map so it stays sellable).
            String name = (dto.getName() == null || dto.getName().isBlank()) ? sku : dto.getName();

            Product p = Product.builder()
                    .sku(sku)
                    .name(name)
                    .description(dto.getDescription())
                    .unit(dto.getUnit())
                    .manufacturer(dto.getManufacturer())
                    .sellingPrice(dto.getSellingPrice())
                    .taxRate(dto.getTaxRate())
                    .category(resolveCategory(dto.getCategoryName(), orgId, userId))
                    .isActive(true)
                    .organizationId(orgId)
                    .userId(userId)
                    .build();
            Product saved = productRepository.save(p);
            results.add(new ProductImportResult(dto.getClientRef(), saved.getId()));
        }
        return results;
    }

    /** Find-or-create a tenant Category by name; null/blank names yield no category. */
    private Category resolveCategory(String name, Long orgId, Long userId) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        return categoryRepository.findByNameScoped(trimmed, orgId, userId)
                .orElseGet(() -> categoryRepository.save(
                        Category.builder().name(trimmed).organizationId(orgId).userId(userId).build()));
    }
}
