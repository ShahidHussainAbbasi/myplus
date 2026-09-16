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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final com.myplus.catalog.repository.TaxCodeRepository taxCodeRepository;   // multi-rate tax: resolve rate from code
    // CACHE-1 — the picker's tenant-scoped page cache, and the event every writer here publishes so it is evicted
    // AFTER the commit (ProductPickerCache.onProductsChanged), never inside the transaction.
    private final ProductPickerCache pickerCache;
    private final org.springframework.context.ApplicationEventPublisher events;
    // CACHE-2 — the manufacturers list (distinct values on this tenant's products), evicted by the same
    // CatalogProductsChanged every writer here already publishes; and the category list, which findOrCreateCategory
    // writes to.
    private final CatalogRefsCache refsCache;

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

    /**
     * PERF-8 — active products, projected to the three fields a picker needs.
     *
     * <p>No {@code map(this::toDto)}: the projection happens in the query, so nothing wider than these three
     * columns is ever loaded. Same tenant scoping as {@link #getAll} — org, with the NULL/user fallback.
     */
    public Page<com.myplus.catalog.dto.ProductPickerDTO> getPicker(Pageable pageable) {
        Long org = CurrentUser.organizationId();
        Long user = CurrentUser.userId();
        // CACHE-1 — cache-aside: this tenant + user + page from the cache; on a miss the database, kept with a TTL.
        return pickerCache.page(org, user, pageable, () -> productRepository.findPickerScoped(org, user, pageable));
    }

    /**
     * CACHE-1 — tell the picker cache a product changed. Called INSIDE the writer's transaction; the cache evicts only
     * once that transaction commits (ProductPickerCache.onProductsChanged), so a rollback evicts nothing.
     * Both orgs: the caller's (whose pages show the row through the scope's user leg) and the product's own.
     */
    private void changed(Product p) {
        events.publishEvent(CatalogProductsChanged.of(CurrentUser.organizationId(), p.getOrganizationId()));
    }

    /** M4e.c (slice 103): tenant-scoped product count for the dashboard KPI. */
    @Transactional(readOnly = true)
    public long count() {
        return productRepository.countScoped(CurrentUser.organizationId(), CurrentUser.userId());
    }

    /**
     * Products per category for the dashboard card, biggest first.
     *
     * <p>One grouped query, not a count per category: a tenant with 39 categories would otherwise cost 39
     * round trips to draw one card.
     */
    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> categoryCounts() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object[] row : productRepository.countByCategoryScoped(
                CurrentUser.organizationId(), CurrentUser.userId())) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("categoryId", row[0]);
            // Null id AND null name is the uncategorised bucket. Named on the SERVER so every caller shows
            // the same words \u2014 a client inventing its own label would drift from the next one.
            m.put("categoryName", row[1] != null ? row[1] : "Uncategorised");
            m.put("uncategorised", row[0] == null);
            m.put("count", row[2] == null ? 0L : ((Number) row[2]).longValue());
            out.add(m);
        }
        return out;
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

        /*
         * DUP-1 — the FAST path of idempotent creation: this form-fill already produced a product, so return
         * that one instead of a second copy.
         *
         * ⚠ THIS CHECK ALONE DOES NOT FIX THE DEFECT IT WAS WRITTEN FOR, and believing otherwise is the easy
         * mistake here. A production shop registered 148 products from one submit by holding Enter: those
         * requests were all IN FLIGHT AT ONCE, so every one of their pre-checks found nothing and all 148
         * inserted. What actually arbitrates between concurrent twins is the UNIQUE index from V16, and the
         * loser's replay lives in ProductController.create — it cannot live here, because a constraint
         * violation marks THIS transaction rollback-only and no read inside it can succeed afterwards.
         *
         * So: this path catches the sequential repeat (a retry after the first one committed), the index plus
         * the controller catches the concurrent one. Both are needed; neither is sufficient.
         */
        String key = normalize(dto.getIdempotencyKey());
        if (key != null) {
            Optional<Product> already = productRepository.findByIdempotencyKeyScoped(key, orgId, userId);
            if (already.isPresent()) {
                LOG.info("create: idempotent replay for key {} -> existing product {}", key, already.get().getId());
                return toDto(already.get());
            }
        }

        // Only a REAL sku can be a duplicate. Checking a blank one matched every other product saved
        // without a code, so the second such product was rejected with "SKU already exists: ".
        String sku = normalize(dto.getSku());
        if (sku != null && productRepository.existsBySkuScoped(sku, orgId, userId)) {
            throw new DuplicateResourceException("Product SKU already exists: " + sku);
        }
        Product p = fromDto(dto, new Product());
        p.setOrganizationId(orgId);
        p.setUserId(userId);
        p.setIdempotencyKey(key);     // stamped HERE, not in fromDto: an update must never move the key
        if (p.getCreatedBy() == null) p.setCreatedBy(userId);
        /*
         * saveAndFlush, deliberately: the INSERT must hit the database while we are still inside this method so
         * the unique-index violation surfaces as a DataIntegrityViolationException the controller can replay.
         * A plain save() may defer the insert to commit — which happens after this method returns, turning a
         * recoverable duplicate into an opaque 500.
         */
        Product saved = productRepository.saveAndFlush(p);
        changed(saved);   // CACHE-1 — evicted after commit; a duplicate that throws above publishes nothing
        return toDto(saved);
    }

    /**
     * DUP-1 — read back the product a key created, for the controller's replay after a lost insert race.
     * Its own transaction: the caller's has already rolled back.
     */
    @Transactional(readOnly = true)
    public Optional<ProductDTO> findByIdempotencyKey(String key) {
        String k = normalize(key);
        if (k == null) return Optional.empty();
        return productRepository
                .findByIdempotencyKeyScoped(k, CurrentUser.organizationId(), CurrentUser.userId())
                .map(this::toDto);
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

    /**
     * "Is this SKU already taken?" — the Product form asks on focus-out of the SKU field (PS-1b).
     *
     * <p>Mirrors {@link #checkName} deliberately, but answers a DIFFERENT kind of question. A duplicate
     * name is advisory — two products may legitimately share one. <b>A duplicate SKU is REFUSED</b> by
     * {@code create}/{@code update}, so this is the check that decides whether the save can succeed at all.
     *
     * <p>It exists because the client used to answer this from a full-catalogue fetch capped at 1,000 rows:
     * on a 1,632-product tenant <b>632 SKUs were invisible</b> and every one of them passed the check,
     * telling the operator a taken SKU was free and failing only on submit. The server sees every row.
     *
     * <p>{@code excludeId} is the product being EDITED — keeping your own SKU is not a duplicate.
     */
    public NameCheckDTO checkSku(String sku, Long excludeId) {
        String v = normalize(sku);
        if (v == null) return NameCheckDTO.none();   // a blank SKU is "none", never a duplicate
        return productRepository.findBySkuScoped(v, CurrentUser.organizationId(), CurrentUser.userId())
                .filter(p -> excludeId == null || !excludeId.equals(p.getId()))
                .map(p -> new NameCheckDTO(true, p.getId(), p.getName(), p.getSku(),
                        !Boolean.FALSE.equals(p.getIsActive())))
                .orElseGet(NameCheckDTO::none);
    }

    /** The tenant's distinct manufacturer names for the Product form's dropdown (PS-1b). */
    public java.util.List<String> manufacturers() {
        Long org = CurrentUser.organizationId();
        Long user = CurrentUser.userId();
        // CACHE-2 — cache-aside; copied so the shared cached list cannot be changed by a caller.
        return refsCache.manufacturers(org, user,
                () -> java.util.List.copyOf(productRepository.findDistinctManufacturersScoped(org, user)));
    }

    @Transactional
    public ProductDTO update(Long id, ProductDTO dto) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        /*
         * BLK-4 — refuse a save made against a copy somebody else has since replaced.
         *
         * ⚠ AN EXPLICIT COMPARISON, NOT dto.version COPIED ONTO THE ENTITY. `p` is MANAGED — loaded by getEntity
         * inside this transaction — and Hibernate checks the version it LOADED, not whatever is set on the object
         * afterwards. Copying the form's version across would look like a lock and check nothing. @Version still
         * does its own job: it catches a writer that commits between this read and the flush below.
         *
         * A null version falls back to last-write-wins: an older cached tab must keep saving (V62's rule).
         * The refusal is an OptimisticLockingFailureException, which common-web answers as 409 with a message
         * that names the action, and the monolith proxy carries that sentence to the form.
         */
        if (dto.getVersion() != null && !dto.getVersion().equals(p.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Product.class, id);
        }
        // Same rule as create: a blank sku is "cleared", not a duplicate. Clearing the code on an
        // existing product must be allowed, so only a real, CHANGED value is checked.
        String sku = normalize(dto.getSku());
        if (sku != null && !sku.equals(p.getSku())
                && productRepository.existsBySkuScoped(sku, CurrentUser.organizationId(), CurrentUser.userId())) {
            throw new DuplicateResourceException("Product SKU already exists: " + sku);
        }
        fromDto(dto, p);
        /*
         * saveAndFlush, not save: Hibernate increments the version when it FLUSHES, and a plain save() on a managed
         * entity defers that to commit — after toDto has already run. The response would then carry the OLD
         * version, and the next save from that response would be refused as stale against the caller's own edit.
         */
        Product saved = productRepository.saveAndFlush(p);
        changed(saved);   // CACHE-1 — name / price / active may all have changed
        return toDto(saved);
    }

    // PROD-DEL: the plain row delete that lived here is GONE on purpose. Nothing in any database stops a product row
    // being deleted, and its id is stored in 22 columns across 5 databases, so a delete that skipped the usage check
    // orphaned them all silently. Permanent delete is ProductDeletionService, owner-only and usage-checked.

    /**
     * Paged product search \u2014 the grid's read and the dashboard drill's read, deliberately the SAME one.
     *
     * <p>A blank {@code q} is normalised to null so an empty search box means "everything", not "match the
     * empty string" \u2014 which LIKE '%%' happens to do today, until a later rewrite of the clause does not.
     */
    @Transactional(readOnly = true)
    public Page<ProductDTO> search(String q, Long categoryId, boolean uncategorised, boolean includeInactive,
                                   BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        String needle = (q == null || q.isBlank()) ? null : q.trim();
        return productRepository.searchScoped(needle, categoryId, uncategorised, includeInactive,
                minPrice, maxPrice, CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<ProductDTO> getByCategory(Long categoryId, Pageable pageable) {
        return productRepository.findByCategoryScoped(categoryId, CurrentUser.organizationId(), CurrentUser.userId(), pageable).map(this::toDto);
    }

    @Transactional
    public ProductDTO setActive(Long id, boolean active) {
        Product p = getEntity(id);   // scoped — anti-IDOR
        p.setIsActive(active);
        // BLK-4 — saveAndFlush so the response carries the version this write moved to (see update()).
        Product saved = productRepository.saveAndFlush(p);
        changed(saved);   // CACHE-1 — a deactivated product leaves the picker, a reactivated one returns
        return toDto(saved);
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
            // BLK-4 — saveAndFlush: this write moves the version (it is exactly the change an open product form must
            // not overwrite), and the response should say so rather than carry the pre-write version.
            p = productRepository.saveAndFlush(p);
            changed(p);   // CACHE-1 — the cached picker row carries sellingPrice; a no-op purchase publishes nothing
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
        /*
         * C6 — a tenant that does not have the capability may not set the product policy.
         *
         * This was an open gap: ADMIN_PRIVILEGE alone let any tenant mark a product prescription-only,
         * including a hardware shop whose tills would then refuse to sell it for a reason nobody could
         * explain from the product screen. Privilege answers "may this USER write?"; capability answers
         * "does this TENANT do prescription trade at all?" — different questions, both needed.
         *
         * Only checked when the flag is actually BEING SET. An admin editing an unrelated field passes
         * nulls here, and refusing those would make an existing product uneditable the moment a capability
         * was switched off — punishing the tenant for tidying their configuration.
         */
        requireCapability(rxRequired, "rxRequired",
                "Prescription control is not switched on for your business.");
        if (rxRequired != null) p.setRxRequired(rxRequired);
        if (controlledSubstance != null) p.setControlledSubstance(controlledSubstance);
        productRepository.save(p);
        changed(p);   // CACHE-1 — not in the picker row today; evicted anyway so a wider cached row cannot go stale
        return toRef(p, orgCodeRates());
    }

    /**
     * C6 — set the per-product tracking policy: serial/IMEI and batch.
     *
     * <p>Sibling of {@link #updateClinicalFlags}, deliberately: same shape, same guard, same reasoning. Both
     * are per-product policies whose enforcement is <b>tenant capability AND product policy</b>.
     *
     * <p>Null means "leave alone", so a caller can set one flag without restating the other.
     */
    @Transactional
    public com.myplus.commerce.contracts.dto.ProductRef updateTrackingFlags(Long id, Boolean requiresSerial,
                                                                           Boolean tracksBatch) {
        Product p = getEntity(id);
        requireCapability(requiresSerial, "serialTracking",
                "Serial / IMEI tracking is not switched on for your business.");
        requireCapability(tracksBatch, "batchTracking",
                "Batch tracking is not switched on for your business.");
        if (requiresSerial != null) p.setRequiresSerial(requiresSerial);
        if (tracksBatch != null) p.setTracksBatch(tracksBatch);
        productRepository.save(p);
        changed(p);   // CACHE-1 — the cached picker row carries requiresSerial (the till asks for serials from it)
        return toRef(p, orgCodeRates());
    }

    /**
     * Refuse a policy change the tenant's capabilities do not allow.
     *
     * <p>Reads the capability from the JWT claim (C3c) via {@code CurrentUser}, not from a settings store:
     * catalog-service holds no {@code SettingsStore}, and adding a settings table purely to ask a question the
     * token already answers would be a schema created for nothing.
     *
     * <p><b>Permissive when capabilities were never resolved</b> — a token minted before C3c. Refusing there
     * would break tenants holding older tokens for a reason they could neither see nor fix, and the residual
     * gap closes by itself as tokens refresh. This is a CONFIGURATION write; the guards on stock, ledger and
     * tax use {@code assertEnabled}, which fails closed.
     *
     * <p>The message names the capability in owner-facing words and never the settings key — same rule the
     * anti-IDOR reads follow, where the refusal does not describe the tenant's configuration.
     */
    private void requireCapability(Boolean beingSet, String capabilityCode, String message) {
        // C3d: moved to CapabilityGuard once PriceRuleService needed the same rule. Two copies of "may this
        // tenant configure this?" would drift the first time one was edited alone — and the property most
        // likely to drift is the permissive-when-unresolved decision, which is the one that matters.
        com.myplus.catalog.config.CapabilityGuard.requireIfSetting(beingSet, capabilityCode, message);
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
        return getRefs(ids, false);
    }

    /**
     * CACHE-3 — the same batch, cache-aside, with one deliberate way out.
     *
     * <p><b>{@code fresh} exists because this endpoint serves two different kinds of caller.</b> A read screen wants
     * names and prices to paint a grid; the SELL SAGA prices the line a customer pays from the very same ref
     * ({@code SagaSellService}: sellingPrice → catalogPrice → lineRate) and reads {@code rxRequired} to refuse a
     * prescription-only medicine. The user's rule is that nothing decides money or safety from a remembered row, so
     * the saga asks {@code fresh=true} and always reads MySQL. Batching its lookups (CACHE-3 part 1) must not become
     * a back door into this cache.
     *
     * <p>Cached reads are per PRODUCT, not per id-list: the hits are returned from the cache and only the misses are
     * queried — one query, however many were missing. ⚠ The result is therefore in cache-hits-then-misses order, not
     * the caller's; every caller today indexes it by id ({@code CatalogRefs.byId}, and the grids through it), which is
     * why that is safe to do.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.myplus.commerce.contracts.dto.ProductRef> getRefs(java.util.List<Long> ids, boolean fresh) {
        if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
        if (fresh) return loadRefs(ids);

        Long org = CurrentUser.organizationId();
        Long user = CurrentUser.userId();
        var cached = refsCache.refs(org, user);

        java.util.List<com.myplus.commerce.contracts.dto.ProductRef> out = new java.util.ArrayList<>(ids.size());
        java.util.List<Long> misses = new java.util.ArrayList<>();
        for (Long id : ids) {
            if (id == null) continue;
            com.myplus.commerce.contracts.dto.ProductRef hit = cached.getIfPresent(CatalogRefsCache.refKey(id));
            if (hit != null) out.add(hit);
            else misses.add(id);
        }
        if (!misses.isEmpty()) {
            for (com.myplus.commerce.contracts.dto.ProductRef r : loadRefs(misses)) {
                if (r.getId() != null) cached.put(CatalogRefsCache.refKey(r.getId()), r);
                out.add(r);
            }
        }
        return out;
    }

    /** The database read behind both paths: one tax-code resolution for the whole batch, then one scoped query. */
    private java.util.List<com.myplus.commerce.contracts.dto.ProductRef> loadRefs(java.util.List<Long> ids) {
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
                // C6: per-product tracking policy, carried for the same reason as the flags above — the
                // sell and purchase paths already hold this ref and must not call catalog again to decide.
                .requiresSerial(Boolean.TRUE.equals(p.getRequiresSerial()))
                .tracksBatch(Boolean.TRUE.equals(p.getTracksBatch()))
                // U1: carried on the ref the sale already fetches, so no extra call at checkout.
                .packSize(p.getPackSize())
                .looseUnit(p.getLooseUnit())
                .looseUnitPlural(p.getLooseUnitPlural())
                .allowLoose(Boolean.TRUE.equals(p.getAllowLoose()))
                .defaultSellUnit(p.getDefaultSellUnit())
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
                // U1 — read back so the product form ROUND-TRIPS. Without these the form would post null on
                // every save and the "null means not supplied" guard above would be the only thing standing
                // between an edit and silently clearing the pack rules.
                .packSize(p.getPackSize())
                .looseUnit(p.getLooseUnit())
                .looseUnitPlural(p.getLooseUnitPlural())
                .allowLoose(Boolean.TRUE.equals(p.getAllowLoose()))
                .defaultSellUnit(p.getDefaultSellUnit())
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
                // C6 — kept in step with the other toRef builder above. Two builders for one type is a
                // standing drift risk: a field added to one and not the other is invisible until a caller
                // reads a ref that happens to come from the wrong path.
                .requiresSerial(Boolean.TRUE.equals(p.getRequiresSerial()))
                .tracksBatch(Boolean.TRUE.equals(p.getTracksBatch()))
                .imageUrl(p.getImageUrl())
                .createdBy(p.getCreatedBy())
                // DUP-1 — the caller's own key, echoed so a replay is distinguishable from a fresh insert.
                .idempotencyKey(p.getIdempotencyKey())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                // BLK-4 — the version the product form must send back. Deliberately NOT read in fromDto: update()
                // compares it explicitly, and copying it onto a managed entity would check nothing.
                .version(p.getVersion())
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

        /*
         * U1 — the pack fields.
         *
         * ⚠ NULL MEANS "NOT SUPPLIED", NOT "CLEAR IT". Every other setter here overwrites unconditionally,
         * which is right for fields the form always posts. These are new, so a caller written before U1 — the
         * CSV import, the storefront admin, any integration — omits them entirely, and treating that silence
         * as "set pack size to null" would strip the configuration off every product it touched.
         *
         * ⚠ PRICING CONTROLS ARE AUDITED. packSize and allowLoose decide what a customer is charged and
         * whether a sealed course may be split, so a change to either is recorded with WHO and WHEN. Written
         * onto the product itself rather than through audit-service: catalog has no audit client, and adding
         * a cross-service dependency for one field is a larger change than the thing being audited.
         */
        if (dto.getPackSize() != null) {
            if (!java.util.Objects.equals(p.getPackSize(), dto.getPackSize())) {
                p.setPackChangedBy(dto.getUpdatedBy());
                p.setPackChangedAt(java.time.LocalDateTime.now());
            }
            p.setPackSize(dto.getPackSize());
        }
        if (dto.getLooseUnit() != null) p.setLooseUnit(dto.getLooseUnit());
        if (dto.getLooseUnitPlural() != null) p.setLooseUnitPlural(dto.getLooseUnitPlural());
        if (dto.getAllowLoose() != null) {
            if (!Boolean.valueOf(Boolean.TRUE.equals(p.getAllowLoose())).equals(dto.getAllowLoose())) {
                p.setPackChangedBy(dto.getUpdatedBy());
                p.setPackChangedAt(java.time.LocalDateTime.now());
            }
            p.setAllowLoose(dto.getAllowLoose());
        }
        if (dto.getDefaultSellUnit() != null) p.setDefaultSellUnit(dto.getDefaultSellUnit());

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
            Category created = categoryRepository.save(c);
            // CACHE-2 — inside the product create/update transaction; the category list is evicted after its commit.
            events.publishEvent(CatalogCategoriesChanged.of(orgId));
            return created;
        });
    }
}
