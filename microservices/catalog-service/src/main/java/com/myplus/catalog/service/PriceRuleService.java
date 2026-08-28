package com.myplus.catalog.service;

import com.myplus.catalog.dto.PriceRuleDTO;
import com.myplus.catalog.entity.PriceRuleEntity;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.PriceRuleRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.commerce.contracts.dto.PriceQuote;
import com.myplus.commerce.contracts.dto.PriceQuoteLine;
import com.myplus.common.security.CurrentUser;
import com.myplus.commerce.pricing.BasketLine;
import com.myplus.commerce.pricing.PriceResolver;
import com.myplus.commerce.pricing.PriceRule;
import com.myplus.commerce.pricing.PricedLine;
import com.myplus.commerce.pricing.PricingContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract & tiered pricing (slice b2b-P2 = OMS B1 = requirement #10).
 *
 * <p>Owns the rules; the <em>precedence</em> lives in the shared {@code commerce-pricing} library so POS,
 * storefront and pharmacy cannot drift into three different answers to "what does this customer pay?".
 *
 * <h3>The quote is on the checkout hot path, so it costs TWO queries — not two per line</h3>
 * One read for the tenant's active rules, one batch read for the products, then the resolution happens in
 * memory. {@code buildLines} already calls the catalog once per line; adding a price query per line would
 * double that, on every sale, forever.
 */
@Service
@RequiredArgsConstructor
public class PriceRuleService {

    private final PriceRuleRepository repo;
    private final ProductRepository productRepo;

    // ── the quote (hot path) ────────────────────────────────────────────────────────────────────────

    /**
     * Resolve one price per requested line. Never throws for business reasons: an unknown product, an absent
     * rule or a malformed rule all fall back to the catalog price, because the till must always get an answer.
     */
    @Transactional(readOnly = true)
    public PriceQuote quote(PriceQuote request) {
        PriceQuote out = new PriceQuote();
        List<PriceQuoteLine> priced = new ArrayList<>();
        out.setLines(priced);
        if (request == null || request.getLines() == null || request.getLines().isEmpty()) {
            return out;
        }
        out.setCustomerId(request.getCustomerId());
        out.setCustomerType(request.getCustomerType());

        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();

        List<Long> productIds = request.getLines().stream()
                .map(PriceQuoteLine::getProductId)
                .filter(java.util.Objects::nonNull).distinct().toList();

        // Query 1 of 2: the products, in one batch, tenant-scoped.
        Map<Long, Product> products = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Product p : productRepo.findAllByIdScoped(productIds, orgId, userId)) {
                products.put(p.getId(), p);
            }
        }
        // Query 2 of 2: every ACTIVE rule for the tenant. Resolution is then pure in-memory work.
        List<PriceRule> rules = repo.findActiveScoped(orgId, userId).stream()
                .map(PriceRuleService::toLibrary).toList();

        PricingContext ctx = new PricingContext(request.getCustomerId(), request.getCustomerType(),
                LocalDate.now());

        for (PriceQuoteLine reqLine : request.getLines()) {
            Product p = (reqLine.getProductId() == null) ? null : products.get(reqLine.getProductId());
            BigDecimal catalogPrice = (p != null && p.getSellingPrice() != null)
                    ? p.getSellingPrice() : BigDecimal.ZERO;
            Long categoryId = (p != null && p.getCategory() != null) ? p.getCategory().getId() : null;

            BasketLine basketLine = new BasketLine(reqLine.getProductId(), categoryId,
                    reqLine.getQuantity() == null ? BigDecimal.ONE : reqLine.getQuantity(), catalogPrice);
            PricedLine resolved = PriceResolver.resolveOne(rules, ctx, basketLine);
            if (resolved == null) {
                continue;
            }
            PriceQuoteLine line = PriceQuoteLine.of(resolved.productId(), reqLine.getQuantity());
            line.setUnitPrice(resolved.unitPrice());
            line.setSource(resolved.source());
            line.setRuleId(resolved.ruleId());
            line.setReason(resolved.reason());
            priced.add(line);
        }
        return out;
    }

    // ── CRUD ────────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PriceRuleDTO> list() {
        return repo.findScoped(CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(PriceRuleService::toDto).toList();
    }

    @Transactional
    public PriceRuleDTO create(PriceRuleDTO dto) {
        /*
         * C3d — tiered / dealer pricing is a capability, and a price rule is how a tenant uses it.
         *
         * Hiding the Price Rules screen never stopped a caller with the URL, and a rule written without the
         * capability quietly changes what customers are charged: the pricing path applies whatever rules
         * exist, with no second opinion about whether the tenant was entitled to create them.
         *
         * Guarded in the SERVICE rather than the controller so both write paths are covered by one check,
         * and so it sits inside the same transaction as the write it protects.
         */
        com.myplus.catalog.config.CapabilityGuard.require("dealerPricing",
                "Dealer and tier pricing is not switched on for your business.");
        validate(dto);
        PriceRuleEntity e = new PriceRuleEntity();
        apply(dto, e);
        e.setOrganizationId(CurrentUser.organizationId());
        e.setUserId(CurrentUser.userId());
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return toDto(repo.save(e));
    }

    @Transactional
    public PriceRuleDTO update(Long id, PriceRuleDTO dto) {
        // Guarded like create(): editing an existing rule changes prices just as effectively as adding one,
        // and a tenant whose capability was withdrawn must not keep tuning the rules it left behind.
        com.myplus.catalog.config.CapabilityGuard.require("dealerPricing",
                "Dealer and tier pricing is not switched on for your business.");
        validate(dto);
        // Anti-IDOR: scoped by-id read, so an edit can never reach another tenant's rule.
        PriceRuleEntity e = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new IllegalArgumentException("Price rule not found"));
        apply(dto, e);
        e.setUpdatedAt(LocalDateTime.now());
        return toDto(repo.save(e));
    }

    @Transactional
    public void delete(Long id) {
        PriceRuleEntity e = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new IllegalArgumentException("Price rule not found"));
        repo.delete(e);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reject a rule that cannot mean anything, at the point it is CREATED rather than silently ignoring it at
     * checkout. The resolver already falls back safely, but a rule the owner believes they set and which never
     * fires is worse than an error message.
     */
    private void validate(PriceRuleDTO dto) {
        if (dto == null) throw new IllegalArgumentException("No rule supplied");
        String scope = up(dto.getScope());
        String target = up(dto.getTarget());
        String mode = up(dto.getMode());

        if (!"CUSTOMER".equals(scope) && !"TYPE".equals(scope))
            throw new IllegalArgumentException("Scope must be CUSTOMER or TYPE");
        if ("CUSTOMER".equals(scope) && dto.getCustomerId() == null)
            throw new IllegalArgumentException("Choose the customer this rule applies to");
        if ("TYPE".equals(scope) && (dto.getCustomerType() == null || dto.getCustomerType().isBlank()))
            throw new IllegalArgumentException("Choose the customer type this rule applies to");

        if (!"PRODUCT".equals(target) && !"CATEGORY".equals(target))
            throw new IllegalArgumentException("Target must be PRODUCT or CATEGORY");
        if ("PRODUCT".equals(target) && dto.getProductId() == null)
            throw new IllegalArgumentException("Choose the product this rule applies to");
        if ("CATEGORY".equals(target) && dto.getCategoryId() == null)
            throw new IllegalArgumentException("Choose the category this rule applies to");

        if (!"FIXED".equals(mode) && !"PERCENT".equals(mode))
            throw new IllegalArgumentException("Mode must be FIXED or PERCENT");
        if (dto.getValue() == null || dto.getValue().signum() < 0)
            throw new IllegalArgumentException("Enter a value of zero or more");
        if ("PERCENT".equals(mode) && dto.getValue().compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("A discount cannot exceed 100%");

        if (dto.getStartsOn() != null && dto.getEndsOn() != null && dto.getEndsOn().isBefore(dto.getStartsOn()))
            throw new IllegalArgumentException("The end date cannot be before the start date");
    }

    private void apply(PriceRuleDTO dto, PriceRuleEntity e) {
        String scope = up(dto.getScope());
        String target = up(dto.getTarget());
        e.setScope(scope);
        e.setTarget(target);
        e.setMode(up(dto.getMode()));
        e.setValue(dto.getValue());
        // Null the key that does not belong to the chosen scope/target, so a rule edited from CUSTOMER to
        // TYPE cannot keep a stale customerId that would make it match two different ways.
        e.setCustomerId("CUSTOMER".equals(scope) ? dto.getCustomerId() : null);
        e.setCustomerType("TYPE".equals(scope) ? up(dto.getCustomerType()) : null);
        e.setProductId("PRODUCT".equals(target) ? dto.getProductId() : null);
        e.setCategoryId("CATEGORY".equals(target) ? dto.getCategoryId() : null);
        e.setPriority(dto.getPriority() == null ? 0 : dto.getPriority());
        e.setActive(dto.getActive() == null || dto.getActive());
        e.setStartsOn(dto.getStartsOn());
        e.setEndsOn(dto.getEndsOn());
    }

    /** Map the persisted row onto the library's carrier. The library never sees JPA, and never sees an org id. */
    static PriceRule toLibrary(PriceRuleEntity e) {
        PriceRule r = new PriceRule();
        r.setId(e.getId());
        r.setScope(safeEnum(PriceRule.Scope.class, e.getScope()));
        r.setCustomerId(e.getCustomerId());
        r.setCustomerType(e.getCustomerType());
        r.setTarget(safeEnum(PriceRule.Target.class, e.getTarget()));
        r.setProductId(e.getProductId());
        r.setCategoryId(e.getCategoryId());
        r.setMode(safeEnum(PriceRule.Mode.class, e.getMode()));
        r.setValue(e.getValue());
        r.setPriority(e.getPriority() == null ? 0 : e.getPriority());
        r.setActive(e.getActive() == null || e.getActive());
        r.setStartsOn(e.getStartsOn());
        r.setEndsOn(e.getEndsOn());
        return r;
    }

    /** An unrecognised stored value yields null, which the resolver treats as "does not match" — never a crash. */
    private static <E extends Enum<E>> E safeEnum(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static PriceRuleDTO toDto(PriceRuleEntity e) {
        return PriceRuleDTO.builder()
                .id(e.getId()).scope(e.getScope())
                .customerId(e.getCustomerId()).customerType(e.getCustomerType())
                .target(e.getTarget()).productId(e.getProductId()).categoryId(e.getCategoryId())
                .mode(e.getMode()).value(e.getValue())
                .priority(e.getPriority()).active(e.getActive())
                .startsOn(e.getStartsOn()).endsOn(e.getEndsOn())
                .build();
    }

    private static String up(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}
