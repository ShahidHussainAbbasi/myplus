package com.myplus.catalog.controller;

import com.myplus.catalog.entity.Product;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ONB-3 — what a business-type change would cost, and how to clean up after one.
 *
 * <h3>Why this exists in catalog-service and not in auth</h3>
 * auth-service owns the business type; catalog owns the products whose policies the change would strand. auth
 * holds no client to catalog and must not grow one — it is the identity service, depended <i>upon</i> rather
 * than depending outward. So each service counts its own data and the monolith BFF composes the answer, which
 * is what a Backend-for-Frontend is for.
 *
 * <h3>⚠ The org parameter is honoured ONLY for a platform operator</h3>
 * A platform operator legitimately asks about somebody else's tenant — that is the whole point of a migration
 * preview. Every other caller's {@code organizationId} is <b>ignored</b> and resolves to their own org, via
 * {@link CurrentUser#organizationIdFor(Long)}. Without that rule these endpoints would be a cross-tenant read
 * of a competitor's catalogue and, worse, {@link #clearTrackingFlags} would be a cross-tenant <b>write</b>:
 * any tenant owner could clear another shop's serial policy with one query parameter.
 *
 * <p>Ignored rather than rejected, deliberately: a tenant probing with {@code ?organizationId=13} learns
 * nothing from the answer, not even whether 13 exists.
 *
 * <p>The reads themselves still go through {@code ProductRepository.SCOPE} — there is no path here that
 * returns rows from two organizations, and no query that omits the scope.
 */
@RestController
@RequestMapping("/api/catalog/products")
@RequiredArgsConstructor
public class ProductPolicyAdminController {

    private final ProductRepository products;

    /**
     * ONB-3 — how many products carry a policy the tenant may be about to lose.
     *
     * <p>Counts, not rows: this feeds a confirmation dialog, and a tenant can hold thousands of products.
     */
    @GetMapping("/policy-counts")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> policyCounts(@RequestParam(required = false) Long organizationId) {
        Long org = CurrentUser.organizationIdFor(organizationId);
        Long user = CurrentUser.scopeUserIdFor(org);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organizationId", org);
        out.put("requiresSerial", products.countRequiringSerial(org, user));
        out.put("tracksBatch", products.countTrackingBatch(org, user));
        out.put("total", products.countScoped(org, user));
        return ApiResponse.success(out);
    }

    /**
     * ONB-3 — the products a switch would strand, named so somebody can act on them.
     *
     * <p>A warning an operator cannot act on is advice, not a feature. C6 deliberately permits <b>clearing</b>
     * a product policy even without the capability, precisely so a tenant is never left with stock it cannot
     * sell — what was missing is finding which products those are.
     */
    @GetMapping("/policy-conflicts")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> policyConflicts(@RequestParam(required = false) Long organizationId,
                                                            @RequestParam String capability) {
        Long org = CurrentUser.organizationIdFor(organizationId);
        Long user = CurrentUser.scopeUserIdFor(org);

        List<Product> found = "tracksBatch".equalsIgnoreCase(capability) || "batchTracking".equalsIgnoreCase(capability)
                ? products.findTrackingBatch(org, user)
                : products.findRequiringSerial(org, user);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Product p : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("sku", p.getSku());
            m.put("requiresSerial", Boolean.TRUE.equals(p.getRequiresSerial()));
            m.put("tracksBatch", Boolean.TRUE.equals(p.getTracksBatch()));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capability", capability);
        out.put("rows", rows);
        out.put("total", rows.size());
        return ApiResponse.success(out);
    }

    /**
     * ONB-3 — clear a product policy in bulk, for a tenant that no longer holds the capability.
     *
     * <h3>⚠ CLEAR ONLY. This endpoint cannot set a flag, and that is the whole safety property.</h3>
     * C6's rule is that a tenant <b>without</b> a capability may not SET the matching product policy — only
     * remove it — so that withdrawing a capability can never strand stock, while granting oneself a policy
     * stays impossible. An endpoint that could also set would be a way round the capability, offered to
     * exactly the tenants that just lost it.
     *
     * <p>There is deliberately no value parameter. Not "a value parameter that must be false" — none at all,
     * because a parameter that is validated is a parameter somebody eventually stops validating.
     */
    @PostMapping("/clear-tracking-flags")
    @Transactional
    public ApiResponse<Map<String, Object>> clearTrackingFlags(@RequestParam(required = false) Long organizationId,
                                                               @RequestParam String capability) {
        Long org = CurrentUser.organizationIdFor(organizationId);
        Long user = CurrentUser.scopeUserIdFor(org);
        boolean batch = "tracksBatch".equalsIgnoreCase(capability) || "batchTracking".equalsIgnoreCase(capability);

        List<Product> found = batch ? products.findTrackingBatch(org, user) : products.findRequiringSerial(org, user);
        for (Product p : found) {
            if (batch) p.setTracksBatch(false); else p.setRequiresSerial(false);
        }
        products.saveAll(found);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", found.size());
        out.put("capability", capability);
        return ApiResponse.success(out, found.size() + " product(s) updated");
    }
}
