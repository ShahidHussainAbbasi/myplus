package com.myplus.common.service;

import com.myplus.common.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PROD-DEL — "do any of THIS service's rows still reference product {id}?" ONE implementation, auto-applied to
 * every JPA service through {@code common-service}, the same way {@link DemoPurgeController} is.
 *
 * <h3>Why the metamodel and not a table list per service</h3>
 * A product id is stored in 22 columns across 5 databases (traced 2026-09-14). A hand-written list per service is
 * exactly what the NEXT table storing a product would be missing, and the result would be a permanent delete that
 * orphans its rows with no error anywhere. Walking every entity that has a {@code productId},
 * {@code triggerProductId} or {@code rewardProductId} attribute protects tables that do not exist yet.
 *
 * <h3>⚠ Counted by product id ONLY, never narrowed by organization</h3>
 * The caller (catalog) asks only after resolving the product INSIDE the caller's tenant, and product ids are
 * globally unique, so an id names exactly one tenant's product. An organization filter could only LOWER the count,
 * for example missing a legacy row whose {@code organization_id} is NULL, and a lower count here means a delete
 * that orphans. Conservative is correct: the answer is a count, never a row.
 *
 * <h3>Trust boundary</h3>
 * {@code /internal/**} has no gateway route. The identity arrives forwarded, and a request that names no tenant is
 * refused rather than answered. Design: microservices/docs/slices/prod-del-product-permanent-delete.md §3.2.
 */
@RestController
@RequestMapping("/internal/product-usage")
public class ProductUsageController {

    private static final Logger LOG = LoggerFactory.getLogger(ProductUsageController.class);

    /** Attributes that hold a catalog product id. */
    private static final List<String> PRODUCT_ATTRIBUTES = List.of("productId", "triggerProductId", "rewardProductId");

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/{productId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> usage(@PathVariable Long productId) {
        if (CurrentUser.organizationId() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "No tenant identity on the request"));
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EntityType<?> et : em.getMetamodel().getEntities()) {
            List<String> hits = new ArrayList<>();
            for (String name : PRODUCT_ATTRIBUTES) {
                Attribute<?, ?> a = attribute(et, name);
                if (a == null) continue;
                Class<?> type = a.getJavaType();
                if (type == Long.class || type == long.class) {
                    hits.add(name);
                } else {
                    // Never silently skipped: an unreadable column is treated as "cannot confirm unused".
                    throw new IllegalStateException(et.getName() + "." + name + " is " + type.getSimpleName()
                            + ", not a numeric product id; usage cannot be confirmed");
                }
            }
            if (hits.isEmpty()) continue;
            String where = hits.stream().map(h -> "e." + h + " = :pid").collect(Collectors.joining(" or "));
            Long n = em.createQuery("select count(e) from " + et.getName() + " e where " + where, Long.class)
                    .setParameter("pid", productId)
                    .getSingleResult();
            if (n != null && n > 0) counts.put(label(et.getName()), n);
        }
        LOG.debug("product-usage {} -> {}", productId, counts);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("counts", counts);
        return ResponseEntity.ok(body);
    }

    private static Attribute<?, ?> attribute(EntityType<?> et, String name) {
        try {
            return et.getAttribute(name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    /** "StockLevel" -> "stock level records", "PriceRuleEntity" -> "price rule records": readable by an owner. */
    static String label(String entityName) {
        String base = entityName.endsWith("Entity") ? entityName.substring(0, entityName.length() - 6) : entityName;
        return base.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase() + " records";
    }
}
