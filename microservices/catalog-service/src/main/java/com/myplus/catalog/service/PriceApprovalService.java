package com.myplus.catalog.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.catalog.entity.PriceChangeRequest;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.entity.ProductPriceHistory;
import com.myplus.catalog.repository.PriceChangeRequestRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * PR-4 — prices a purchase would set, waiting for the owner. Design: selling-price-per-purchase-analysis.md §11.
 *
 * <p>Three transitions, each with one writer: {@link #propose} (the purchase path), {@link #approve} and {@link #reject}
 * (owner/admin, enforced on the controller). A decided proposal is final. Approving goes through
 * {@link ProductService#updatePrice}, so the version bump (BLK-4), cache eviction (CACHE-1) and the history row are the
 * purchase path's own, with source {@link ProductPriceHistory#APPROVAL}.
 */
@Service
@RequiredArgsConstructor
public class PriceApprovalService {

    private static final Set<String> STATUSES = Set.of(PriceChangeRequest.PENDING, PriceChangeRequest.APPROVED,
            PriceChangeRequest.REJECTED, PriceChangeRequest.SUPERSEDED);
    private static final Set<String> SOURCES = Set.of(ProductPriceHistory.MARKUP, ProductPriceHistory.PURCHASE);
    private static final Set<String> REASONS = Set.of(PriceChangeRequest.REASON_APPROVAL,
            PriceChangeRequest.REASON_NEVER_LOWER, PriceChangeRequest.REASON_MAX_RISE);

    private final PriceChangeRequestRepository requests;
    private final ProductRepository productRepository;
    private final ProductService productService;

    /**
     * Record that a purchase would set {@code proposed}. Returns null when there is nothing to decide — the proposal
     * equals the price now. A pending proposal for the same product is SUPERSEDED: the latest cost is the one to judge.
     */
    @Transactional
    public Map<String, Object> propose(Long productId, BigDecimal proposed, BigDecimal cost, String source, String reason,
                                       String detail, String ref) {
        if (proposed == null || proposed.signum() <= 0) throw new ValidationException("A proposed price must be above zero.");
        Product p = productService.getEntity(productId);   // scoped: another tenant's product is not found
        BigDecimal current = p.getSellingPrice();
        if (current != null && current.compareTo(proposed) == 0) return null;
        LocalDateTime now = LocalDateTime.now();
        for (PriceChangeRequest older : requests.findPendingForProduct(productId, CurrentUser.organizationId())) {
            older.setStatus(PriceChangeRequest.SUPERSEDED);
            older.setDecidedAt(now);
            requests.save(older);
        }
        PriceChangeRequest r = new PriceChangeRequest();
        r.setOrganizationId(CurrentUser.organizationId());
        r.setUserId(CurrentUser.userId());
        r.setProductId(productId);
        r.setCurrentPrice(current);
        r.setProposedPrice(proposed.setScale(2, RoundingMode.HALF_UP));
        r.setCost(cost);
        r.setSource(SOURCES.contains(source) ? source : ProductPriceHistory.PURCHASE);
        r.setReason(REASONS.contains(reason) ? reason : PriceChangeRequest.REASON_APPROVAL);
        r.setDetail(clip(detail, 160));
        r.setRef(clip(ref, 80));
        r.setStatus(PriceChangeRequest.PENDING);
        r.setProposedBy(CurrentUser.userId());
        r.setProposedAt(now);
        return view(requests.save(r), Map.of(p.getId(), p));
    }

    /** Newest first; {@code status} null = every status. At most 200 rows — a queue, not a report. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (s != null && !STATUSES.contains(s)) throw new ValidationException("Unknown status: " + status);
        List<PriceChangeRequest> rows = requests.findByStatusScoped(s, CurrentUser.organizationId(), PageRequest.of(0, 200));
        Map<Long, Product> products = new HashMap<>();
        for (Product p : productRepository.findAllById(rows.stream().map(PriceChangeRequest::getProductId).distinct().toList())) {
            products.put(p.getId(), p);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceChangeRequest r : rows) out.add(view(r, products));
        return out;
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return requests.countPending(CurrentUser.organizationId());
    }

    /**
     * Set the proposed price. {@code expectedCurrent} is the price the owner saw: if the product's price has moved since
     * (a hand edit, another approval), the approval is refused rather than overwrite a change the owner never saw.
     */
    @Transactional
    public Map<String, Object> approve(Long id, BigDecimal expectedCurrent) {
        PriceChangeRequest r = pending(id);
        Product p = productService.getEntity(r.getProductId());
        BigDecimal now = p.getSellingPrice();
        if (expectedCurrent != null && (now == null || now.compareTo(expectedCurrent) != 0)) {
            throw new ValidationException("The price is now " + money(now) + " — it changed since this was proposed. "
                    + "Reload and decide again.");
        }
        productService.updatePrice(r.getProductId(), r.getProposedPrice(), null, r.getRef(), ProductPriceHistory.APPROVAL);
        decide(r, PriceChangeRequest.APPROVED, null);
        return view(r, Map.of(p.getId(), p));
    }

    /** Record the decision; nothing about the product changes. */
    @Transactional
    public Map<String, Object> reject(Long id, String note) {
        PriceChangeRequest r = pending(id);
        decide(r, PriceChangeRequest.REJECTED, clip(note, 255));
        Product p = productRepository.findById(r.getProductId()).orElse(null);
        return view(r, p == null ? Map.of() : Map.of(p.getId(), p));
    }

    private PriceChangeRequest pending(Long id) {
        PriceChangeRequest r = requests.findScoped(id, CurrentUser.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Price change not found: " + id));
        if (!PriceChangeRequest.PENDING.equals(r.getStatus())) {
            throw new ValidationException("This price change was already decided (" + r.getStatus().toLowerCase() + ").");
        }
        return r;
    }

    private void decide(PriceChangeRequest r, String status, String note) {
        r.setStatus(status);
        r.setDecidedBy(CurrentUser.userId());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionNote(note);
        requests.save(r);
    }

    private static Map<String, Object> view(PriceChangeRequest r, Map<Long, Product> products) {
        Map<String, Object> m = new LinkedHashMap<>();
        Product p = products.get(r.getProductId());
        m.put("id", r.getId());
        m.put("productId", r.getProductId());
        m.put("productName", p == null ? null : p.getName());
        m.put("priceNow", p == null ? null : p.getSellingPrice());   // live: what an approval would replace
        m.put("currentPrice", r.getCurrentPrice());                 // when it was proposed
        m.put("proposedPrice", r.getProposedPrice());
        m.put("changePct", changePct(p == null ? r.getCurrentPrice() : p.getSellingPrice(), r.getProposedPrice()));
        m.put("cost", r.getCost());
        m.put("source", r.getSource());
        m.put("reason", r.getReason());
        m.put("detail", r.getDetail());
        m.put("ref", r.getRef());
        m.put("status", r.getStatus());
        m.put("proposedBy", r.getProposedBy());
        m.put("proposedAt", r.getProposedAt() == null ? null
                : r.getProposedAt().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString());
        m.put("decidedBy", r.getDecidedBy());
        m.put("decidedAt", r.getDecidedAt() == null ? null
                : r.getDecidedAt().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString());
        m.put("decisionNote", r.getDecisionNote());
        return m;
    }

    /** +20.2 for 200 → 240.45; null when there is no price to compare with. */
    static BigDecimal changePct(BigDecimal from, BigDecimal to) {
        if (from == null || from.signum() <= 0 || to == null) return null;
        return to.subtract(from).multiply(BigDecimal.valueOf(100)).divide(from, 1, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        return v == null ? "not set" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > max ? t.substring(0, max) : t);
    }
}
