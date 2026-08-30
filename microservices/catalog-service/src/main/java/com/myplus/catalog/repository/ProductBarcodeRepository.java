package com.myplus.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.catalog.entity.ProductBarcode;

/**
 * U7 — own-sticker codes, always org-scoped.
 *
 * <p>Unlike {@code ProductRepository}, there is <b>no NULL-organisation fallback</b> here. That fallback
 * exists for products created before tenancy landed; this table is new, every row is written with an
 * organisation, and a fallback would only be a way for one tenant's sticker to resolve in another's till.
 */
public interface ProductBarcodeRepository extends JpaRepository<ProductBarcode, Long> {

    /** The scan path's whole query: one indexed probe on (organization_id, barcode). */
    Optional<ProductBarcode> findByOrganizationIdAndBarcode(Long organizationId, String barcode);

    /** U12 — every sticker in the org, for the label sheet. */
    List<ProductBarcode> findByOrganizationIdOrderByBarcodeAsc(Long organizationId);

    /** The stickers registered against one product, for the product form's list. */
    List<ProductBarcode> findByOrganizationIdAndProductIdOrderByBarcodeAsc(Long organizationId, Long productId);

    /**
     * Does any PRODUCT in this org already own this code as its barcode or sku?
     *
     * <p>⚠ The collision check that keeps an alias from shadowing a real product — see
     * {@code ProductBarcode}'s class note for what happens when it does.
     */
    @Query("SELECT COUNT(p) FROM Product p WHERE (p.barcode = :code OR p.sku = :code) "
         + "AND (p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))")
    long countProductsUsingCode(@Param("code") String code,
                                @Param("orgId") Long orgId,
                                @Param("userId") Long userId);
}
