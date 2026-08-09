package com.myplus.catalog.service;

import com.myplus.catalog.dto.NameCheckDTO;
import com.myplus.catalog.dto.ProductDTO;
import com.myplus.catalog.entity.Category;
import com.myplus.catalog.entity.Product;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.catalog.repository.CategoryRepository;
import com.myplus.catalog.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final com.myplus.catalog.repository.TaxCodeRepository taxCodeRepository;   // multi-rate tax: resolve rate from code

    /** This org's tax-code rates by id (one query) — so building refs never does a per-product lookup. */
    private java.util.Map<Long, BigDecimal> orgCodeRates() {
        java.util.Map<Long, BigDecimal> m = new java.util.HashMap<>();
        for (com.myplus.catalog.entity.TaxCode t : taxCodeRepository.findByOrganizationId(CurrentUser.organizationId()))
            m.put(t.getId(), t.getRate() != null ? t.getRate() : BigDecimal.ZERO);
        return m;
    }

    /** The rate to expose for a product: its tax-code's rate when assigned (multi-rate), else the legacy per-product
     *  rate. Keeps the sale/purchase hot paths unchanged — they still read {@code ProductRef.taxRate}. */
    static BigDecimal resolveRate(Product p, java.util.Map<Long, BigDecimal> codeRates) {
        if (p.getTaxCodeId() != null && codeRates != null) {
            BigDecimal r = codeRates.get(p.getTaxCodeId());
            if (r != null) return r;
        }
        return p.getTaxRate();
    }

    // readOnly tx keeps the session open through toDto()'s lazy category access (open-in-view is false) —
    // otherwise listing a product that HAS a category throws "Could not initialize proxy [Category] - no session".
    @Transactional(readOnly = true)
    public Page<ProductDTO> getAll(Pageable pageable) {
        return productRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    /** M4e.c (slice 103): tenant-scoped product count for the dashboard KPI. */
    @Transactional(readOnly = true)
    public long count() {
        return productRepository.countScoped(CurrentUser.organizationId(), CurrentUser.userId());
    }

    @Transactional(readOnly = true)
    public ProductDTO getById(Long id) {
        return toDto(getEntity(id));
    }

    /** Trim to null: an optional code is either a real value or absent — never the empty string. */
    private static String normalize(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @Transactional
    public ProductDTO create(ProductDTO dto) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        // Only a REAL sku can be a duplicate. Checking a blank one matched every other product saved
        // without a code, so the second such product was rejected with "SKU already exists: ".
        String sku = normalize(dto.getSku());
        if (sku != null && productRepository.existsBySkuScoped(sku, orgId, userId)) {
            throw new DuplicateResourceException("Product SKU already exists: " + sku);
        }
        Product p = fromDto(dto, new Product());
        p.setOrganizationId(orgId);
        p.setUserId(userId);
        if (p.getCreatedBy() == null) p.setCreatedBy(userId);
        return toDto(productRepository.save(p));
    }

    /**
     * Server-side duplicate-NAME check for the product form (fired on focus-out of the Name field).
     *
     * <p>Reports, never rejects — see {@link com.myplus.catalog.dto.NameCheckDTO}. {@code excludeId} is the
     * product being edited, so re-saving a product does not flag it against itself. Read-only and scoped, so a
     * caller can only ever be told about a namesake in their own tenant.
     */
    @Transactional(readOnly = true)
    public NameCheckDTO checkName(String name, Long excludeId) {
        String n = normalize(name);
        if (n == null) return NameCheckDTO.none();
        return productRepository.findByNameScoped(n.toLowerCase(), CurrentUser.organizationId(), CurrentUser.userId())
                .stream()
                .filter(p -> excludeId == null || !excludeId.equals(p.getId()))
                .findFirst()
                .map(p -> new NameCheckDTO(true, p.getId(), p.getName(), p.getSku(),
                        !Boolean.FALSE.equals(p.getIsActive())))
                .orElseGet(NameCheckDTO::none);
    }

    @Transactional
    public ProductDTO update(Long id, ProductDTO dto) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        // Same rule as create: a blank sku is "cleared", not a duplicate. Clearing the code on an
        // existing product must be allowed, so only a real, CHANGED value is checked.
        String sku = normalize(dto.getSku());
        if (sku != null && !sku.equals(p.getSku())
                && productRepository.existsBySkuScoped(sku, CurrentUser.organizationId(), CurrentUser.userId())) {
            throw new DuplicateResourceException("Product SKU already exists: " + sku);
        }
        fromDto(dto, p);
        return toDto(productRepository.save(p));
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(getEntity(id));   // scoped — anti-IDOR
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> search(String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return productRepository.searchScoped(q, categoryId, minPrice, maxPrice,
                CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryScoped(categoryId, CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional
    public ProductDTO setActive(Long id, boolean active) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        p.setIsActive(active);
        return toDto(productRepository.save(p));
    }

    /** Re-price on receive (Option B): the purchase/goods-in flow updates the selling price, and stamps BOTH rates
     *  the purchase carried onto the master so the Product list never has to derive them from history.
     *
     *  <p>GUARD — each rate is applied only when positive; a null/≤0 leaves that field untouched (never wipes it),
     *  and a purchase carrying neither rate changes nothing at all. {@code sellingPrice} still moves only with the
     *  sell rate: a purchase-cost-only update must not silently re-price what the shop charges.
     *
     *  <p>{@code lastRateAt} is stamped whenever either rate lands, so the screen can say WHEN it was last bought.
     *  Scoped via getEntity (anti-IDOR). */
    @Transactional
    public ProductDTO updatePrice(Long id, BigDecimal price, BigDecimal purchaseRate) {
        Product p = getEntity(id);
        boolean touched = false;
        if (isPositive(price)) {
            p.setSellingPrice(price);     // the LIVE master price
            p.setLastSaleRate(price);     // …and the record of what this purchase set it to
            touched = true;
        }
        if (isPositive(purchaseRate)) {
            p.setLastPurchaseRate(purchaseRate);
            touched = true;
        }
        if (touched) {
            p.setLastRateAt(java.time.LocalDateTime.now());
            p = productRepository.save(p);
        }
        return toDto(p);
    }

    private static boolean isPositive(BigDecimal v) { return v != null && v.compareTo(BigDecimal.ZERO) > 0; }

    /** Scoped lookup — anti-IDOR. */
    public Product getEntity(Long id) {
        return productRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    /** Lightweight cross-service reference (+ price) for the sell saga (slice 33, U3b).
     *  readOnly tx keeps the session open through toRef()'s lazy category access (open-in-view is false) — a product
     *  that HAS a category otherwise throws "Could not initialize proxy [Category] - no session". */
    @Transactional(readOnly = true)
    public com.myplus.commerce.contracts.dto.ProductRef getRef(Long id) {
        return toRef(getEntity(id), orgCodeRates());   // scoped — 404 if not this tenant's
    }

    /**
     * B1: set a product's pharmacy clinical flags. Catalog is the SINGLE writer for these two — the pharmacy
     * Clinical &amp; Safety screen funnels here rather than keeping a second copy in medicine_clinical, because two
     * sources of truth for a regulatory flag drift silently. Scoped via getEntity (anti-IDOR).
     * Either argument may be null to leave that flag unchanged.
     */
    @Transactional
    public com.myplus.commerce.contracts.dto.ProductRef updateClinicalFlags(Long id, Boolean rxRequired,
                                                                            Boolean controlledSubstance) {
        Product p = getEntity(id);
        if (rxRequired != null) p.setRxRequired(rxRequired);
        if (controlledSubstance != null) p.setControlledSubstance(controlledSubstance);
        productRepository.save(p);
        return toRef(p, orgCodeRates());
    }

    /** Barcode-first sell: resolve a scanned code (barcode or sku, active, scoped) to a ProductRef, or 404. */
    @Transactional(readOnly = true)
    public com.myplus.commerce.contracts.dto.ProductRef lookup(String code) {
        if (code == null || code.isBlank()) throw new ResourceNotFoundException("No code");
        java.util.List<Product> hits = productRepository.findByCodeScoped(
                code.trim(), CurrentUser.organizationId(), CurrentUser.userId());
        if (hits.isEmpty()) throw new ResourceNotFoundException("No product for code: " + code);
        return toRef(hits.get(0), orgCodeRates());
    }

    /** M4d (slice 93): batch refs by id (tenant-scoped) for the POS read screens — one call instead of N. Missing or
     *  foreign ids are simply omitted. readOnly tx keeps the session open for toRef()'s lazy category (see getRef). */
    @Transactional(readOnly = true)
    public java.util.List<com.myplus.commerce.contracts.dto.ProductRef> getRefs(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
        java.util.Map<Long, BigDecimal> codeRates = orgCodeRates();   // resolved once for the batch (no N+1)
        return productRepository.findAllByIdScoped(ids, CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(p -> toRef(p, codeRates)).toList();
    }

    private com.myplus.commerce.contracts.dto.ProductRef toRef(Product p, java.util.Map<Long, BigDecimal> codeRates) {
        return com.myplus.commerce.contracts.dto.ProductRef.builder()
                .id(p.getId()).sku(p.getSku()).name(p.getName()).unit(p.getUnit())
                .sellingPrice(p.getSellingPrice()).taxRate(resolveRate(p, codeRates))
                .description(p.getDescription())
                .category(p.getCategory() != null ? p.getCategory().getName() : null)
                .manufacturer(p.getManufacturer())
                // B1: the sell guard reads these off the ref it already fetches — no extra call at checkout.
                .rxRequired(Boolean.TRUE.equals(p.getRxRequired()))
                .controlledSubstance(Boolean.TRUE.equals(p.getControlledSubstance()))
                .build();
    }

    public ProductDTO toDto(Product p) {
        return ProductDTO.builder()
                .id(p.getId())
                .sku(p.getSku())
                .barcode(p.getBarcode())
                .name(p.getName())
                .description(p.getDescription())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : null)
                .categoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                .unit(p.getUnit())
                .manufacturer(p.getManufacturer())
                .sellingPrice(p.getSellingPrice())
                .taxRate(p.getTaxRate())
                .taxCodeId(p.getTaxCodeId())
                .isActive(p.getIsActive())
                .lastPurchaseRate(p.getLastPurchaseRate())
                .lastSaleRate(p.getLastSaleRate())
                .lastRateAt(p.getLastRateAt())
                .rxRequired(Boolean.TRUE.equals(p.getRxRequired()))
                .controlledSubstance(Boolean.TRUE.equals(p.getControlledSubstance()))
                .imageUrl(p.getImageUrl())
                .createdBy(p.getCreatedBy())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private Product fromDto(ProductDTO dto, Product p) {
        // SKU is OPTIONAL. Store blank as NULL, exactly as barcode already does: '' is a value that
        // collides with every other blank-SKU product, whereas NULL is "not set" and any number of
        // products may share it.
        p.setSku(normalize(dto.getSku()));
        p.setBarcode(normalize(dto.getBarcode()));
        p.setName(dto.getName());
        p.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) {
            Category cat = categoryRepository.findByIdScoped(dto.getCategoryId(), CurrentUser.organizationId(), CurrentUser.userId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + dto.getCategoryId()));
            p.setCategory(cat);
        } else if (dto.getCategoryName() != null && !dto.getCategoryName().isBlank()) {
            // The POS Product form submits a free-text category NAME (no id) — find-or-create it (tenant-scoped) so
            // the category persists and round-trips to the list + edit form. Blank/absent name leaves it unchanged.
            p.setCategory(findOrCreateCategory(dto.getCategoryName().trim()));
        }
        p.setUnit(dto.getUnit());
        p.setManufacturer(dto.getManufacturer());
        p.setSellingPrice(dto.getSellingPrice());
        p.setTaxRate(dto.getTaxRate());
        p.setTaxCodeId(dto.getTaxCodeId());   // multi-rate tax: assigned code (null clears → taxRate/org default)
        if (dto.getIsActive() != null) p.setIsActive(dto.getIsActive());
        p.setImageUrl(dto.getImageUrl());
        if (dto.getCreatedBy() != null) p.setCreatedBy(dto.getCreatedBy());
        return p;
    }

    /** Find-or-create a tenant-scoped Category by name (for the POS Product form's free-text category). */
    private Category findOrCreateCategory(String name) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        return categoryRepository.findByNameScoped(name, orgId, userId).orElseGet(() -> {
            Category c = new Category();
            c.setName(name);
            c.setOrganizationId(orgId);
            c.setUserId(userId);
            return categoryRepository.save(c);
        });
    }
}
