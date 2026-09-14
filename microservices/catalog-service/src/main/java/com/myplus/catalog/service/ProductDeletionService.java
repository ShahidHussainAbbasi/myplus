package com.myplus.catalog.service;

import com.myplus.catalog.config.ProductUsageClients;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.BonusSchemeRepository;
import com.myplus.catalog.repository.PriceRuleRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.commerce.contracts.dto.ProductUsage;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PROD-DEL — delete a DEACTIVATED product permanently, and refuse while anything still references it.
 *
 * <p>The user's ruling (2026-09-14): an active product's Delete deactivates it; a deactivated product's Delete
 * removes it permanently, owner only, and only when nothing uses it. Stickers go with it; a price rule or bonus
 * scheme keeps it. Design: microservices/docs/slices/prod-del-product-permanent-delete.md.
 *
 * <h3>Why this is enforced HERE and not only on the screen</h3>
 * Nothing in any database stops a product row being deleted: no live foreign key references {@code products}
 * (checked), and its ids sit in 22 columns across 5 databases. A delete that skipped this check would succeed and
 * silently orphan invoices, stock history and orders. STANDARDS §0c question 5: an irreversible act gets its
 * control on the server.
 *
 * <h3>Remote checks OUTSIDE the transaction</h3>
 * Asking up to four services inside a transaction would hold a database connection across HTTP calls. This bean
 * is not transactional: it gathers the answers, then {@link ProductDeletionWriter} re-checks the local conditions
 * and deletes inside one short transaction.
 */
@Service
@RequiredArgsConstructor
public class ProductDeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductDeletionService.class);

    public enum Outcome { DELETED, ALREADY_REMOVED }

    private final ProductRepository productRepository;
    private final PriceRuleRepository priceRuleRepository;
    private final BonusSchemeRepository bonusSchemeRepository;
    private final ProductUsageClients usageClients;
    private final ProductDeletionWriter writer;

    public Outcome deletePermanently(Long id) {
        Product p = productRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElse(null);
        // Idempotent. A retry after a timeout, a second click, or an id from another tenant all get the same
        // answer, and none of them reveals whether the id ever existed elsewhere.
        if (p == null) return Outcome.ALREADY_REMOVED;

        String name = display(p);
        requireDeactivated(p, name);
        requireNoRulesOrSchemes(id, name, priceRuleRepository.countByProductId(id),
                bonusSchemeRepository.countReferencing(id));

        Map<String, Long> usage = gatherUsage(id, name);
        if (!usage.isEmpty()) {
            throw new ValidationException(name + " is kept: it is still used by " + describe(usage)
                    + ". Only a product nothing refers to can be deleted permanently.");
        }
        return writer.delete(id) ? Outcome.DELETED : Outcome.ALREADY_REMOVED;
    }

    static void requireDeactivated(Product p, String name) {
        if (!Boolean.FALSE.equals(p.getIsActive())) {
            throw new ValidationException("Deactivate " + name + " first: only a deactivated product can be deleted permanently.");
        }
    }

    static void requireNoRulesOrSchemes(Long id, String name, long priceRules, long bonusSchemes) {
        if (priceRules + bonusSchemes == 0) return;
        StringBuilder s = new StringBuilder(name).append(" is kept: it is named by ");
        if (priceRules > 0) s.append(priceRules).append(priceRules == 1 ? " price rule" : " price rules");
        if (priceRules > 0 && bonusSchemes > 0) s.append(" and ");
        if (bonusSchemes > 0) s.append(bonusSchemes).append(bonusSchemes == 1 ? " bonus scheme" : " bonus schemes");
        throw new ValidationException(s.append(". Remove those first.").toString());
    }

    /**
     * Every consulted service's non-zero counts, merged. A REQUIRED service that does not answer refuses the delete
     * (fail closed). An OPTIONAL service is asked only when registered, and must answer once it is.
     */
    Map<String, Long> gatherUsage(Long id, String name) {
        Map<String, Long> merged = new LinkedHashMap<>();
        for (String svc : usageClients.required()) merge(merged, ask(svc, id, name));
        for (String svc : usageClients.optional()) {
            if (usageClients.registered(svc)) merge(merged, ask(svc, id, name));
        }
        return merged;
    }

    private ProductUsage ask(String serviceId, Long id, String name) {
        try {
            ProductUsage u = usageClients.clientFor(serviceId).usage(id);
            if (u == null) throw new IllegalStateException("empty answer");
            return u;
        } catch (Exception e) {
            LOG.warn("product-usage: {} did not answer for product {} ({}) — refusing the delete", serviceId, id, e.toString());
            throw new ValidationException("Could not confirm " + name + " is unused (" + serviceId
                    + " did not answer), so it was not deleted. Try again shortly.");
        }
    }

    private static void merge(Map<String, Long> into, ProductUsage u) {
        if (u.getCounts() == null) return;
        u.getCounts().forEach((label, n) -> { if (n != null && n > 0) into.merge(label, n, Long::sum); });
    }

    /** "3 stock level records, 1 sell records": the largest first, at most four named. */
    static String describe(Map<String, Long> usage) {
        List<Map.Entry<String, Long>> top = usage.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(4).toList();
        String s = top.stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        return usage.size() > 4 ? s + " and more" : s;
    }

    private static String display(Product p) {
        return p.getName() == null || p.getName().isBlank() ? "Product #" + p.getId() : "\"" + p.getName() + "\"";
    }
}
