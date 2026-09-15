package com.myplus.catalog.service;

import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.BonusSchemeRepository;
import com.myplus.catalog.repository.PriceRuleRepository;
import com.myplus.catalog.repository.ProductBarcodeRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.audit.AuditRecord;
import com.myplus.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PROD-DEL — the one transaction that removes a product, after {@link ProductDeletionService} has confirmed no
 * other service still references it.
 *
 * <p>A separate bean on purpose: {@code @Transactional} on a method the service calls on itself would be ignored
 * (Spring's proxy is bypassed), and the remote usage checks must stay outside the transaction.
 *
 * <p>It re-checks the LOCAL conditions inside the transaction (still in this tenant, still deactivated, still no
 * price rule or bonus scheme), because they could have changed while the remote checks ran. The audit row is
 * written in the same transaction and delivered after commit, so a delete without its record cannot exist.
 */
@Component
@RequiredArgsConstructor
public class ProductDeletionWriter {

    public static final String ACTION = "PRODUCT_DELETED";
    public static final String ENTITY = "PRODUCT";

    private final ProductRepository productRepository;
    private final ProductBarcodeRepository productBarcodeRepository;
    private final PriceRuleRepository priceRuleRepository;
    private final BonusSchemeRepository bonusSchemeRepository;
    private final CatalogAuditService auditService;
    // CACHE-1 — a deleted product must leave the cached picker pages once this transaction commits.
    private final org.springframework.context.ApplicationEventPublisher events;

    /** @return false when the product had already gone (a concurrent delete won), true when this call removed it */
    @Transactional
    public boolean delete(Long id) {
        Product p = productRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElse(null);
        if (p == null) return false;
        String name = p.getName() == null || p.getName().isBlank() ? "Product #" + id : "\"" + p.getName() + "\"";
        ProductDeletionService.requireDeactivated(p, name);
        ProductDeletionService.requireNoRulesOrSchemes(id, name, priceRuleRepository.countByProductId(id),
                bonusSchemeRepository.countReferencing(id));

        // Stickers are the product's own labels, meaningless without it: they go with it (the user's ruling).
        int stickers = productBarcodeRepository.deleteByProductId(id);
        productRepository.delete(p);
        events.publishEvent(CatalogProductsChanged.of(CurrentUser.organizationId(), p.getOrganizationId()));

        auditService.record(AuditRecord.builder()
                .action(ACTION)
                .entityType(ENTITY)
                .entityRef(String.valueOf(id))
                .details("name=" + p.getName() + (p.getSku() != null ? ", sku=" + p.getSku() : "")
                        + ", stickers removed=" + stickers)
                .build());
        return true;
    }
}
