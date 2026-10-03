package com.myplus.market.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.market.dto.MarketDtos.AgreementView;
import com.myplus.market.dto.MarketDtos.ApplyRequest;
import com.myplus.market.dto.MarketDtos.PolicyView;
import com.myplus.market.dto.MarketDtos.SellerView;
import com.myplus.market.entity.MarketPolicy;
import com.myplus.market.entity.PolicyType;
import com.myplus.market.entity.SellerAgreement;
import com.myplus.market.entity.SellerProfile;
import com.myplus.market.entity.SellerStatus;
import com.myplus.market.repository.SellerAgreementRepo;
import com.myplus.market.repository.SellerProfileRepo;

import lombok.RequiredArgsConstructor;

/**
 * MP-0b — a tenant applies to sell on the marketplace; the operator decides.
 *
 * <h3>What an application proves</h3>
 * That the seller accepted the CURRENT published version of every policy that binds a seller (seller agreement,
 * data sharing, commission, returns). The client sends the ids it showed the owner; if any is not the current
 * version the application is refused with the name of what changed — an owner never "accepts" a version they
 * were not shown. Acceptances are append-only rows (who, which version, when).
 *
 * <h3>Approval re-checks the same thing</h3>
 * A policy can be republished while an application waits. Approving a seller who accepted v1 of an agreement
 * that is now v2 would put a seller on the marketplace under terms they never saw, so approval is refused until
 * the seller re-applies.
 */
@Service
@RequiredArgsConstructor
public class SellerOnboardingService {

    private final SellerProfileRepo profiles;
    private final SellerAgreementRepo agreements;
    private final PolicyService policies;
    private final MarketAccess access;
    private final MarketAuditService audit;

    // ── seller side ───────────────────────────────────────────────────────────────────────────────

    /** The published versions a seller must accept now, in display order. */
    @Transactional(readOnly = true)
    public List<PolicyView> policiesToAccept() {
        access.assertSellingOn();
        return requiredCurrent().values().stream().map(PolicyService::view).toList();
    }

    /** The caller's own profile, or null when it has not applied. Never another org's. */
    @Transactional(readOnly = true)
    public SellerView myProfile() {
        Long org = access.sellerOrg();
        return profiles.findBySellerOrganizationId(org).map(this::view).orElse(null);
    }

    @Transactional
    public SellerView apply(ApplyRequest r) {
        access.assertSellingOn();
        Long org = access.sellerOrg();
        if (org.equals(access.platformOrg())) {
            throw new ValidationException("The marketplace operator does not sell through a seller application. "
                    + "Platform stock arrives in a later phase.");
        }
        if (r == null) throw new ValidationException("Application details are required.");
        String name = required(r.displayName(), "Enter the shop name customers will see.", 120);
        String phone = required(r.contactPhone(), "Enter a contact phone number.", 32);
        String email = PolicyService.trimToNull(r.contactEmail());
        if (email != null && (email.length() > 160 || !email.contains("@"))) {
            throw new ValidationException("Enter a valid contact email, or leave it empty.");
        }
        String city = required(r.city(), "Enter the city you deliver in.", 80);
        String address = required(r.pickupAddress(), "Enter the address orders are collected from.", 300);
        Integer radius = r.serviceRadiusKm();
        if (radius == null || radius < 1 || radius > 100) {
            throw new ValidationException("Service radius must be between 1 and 100 km.");
        }

        Map<PolicyType, MarketPolicy> current = requiredCurrent();
        Set<Long> accepted = r.acceptedPolicyIds() == null ? Set.of() : new HashSet<>(r.acceptedPolicyIds());
        for (MarketPolicy p : current.values()) {
            if (!accepted.contains(p.getId())) {
                throw new ValidationException("Please read and accept \"" + p.getTitle() + "\" (version "
                        + p.getVersionNo() + ") to apply.");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        SellerProfile s = profiles.findBySellerOrganizationId(org).orElse(null);
        String before;
        if (s == null) {
            s = new SellerProfile();
            s.setSellerOrganizationId(org);
            s.setCreatedAt(now);
            before = null;
        } else {
            SellerStatus st = s.statusEnum();
            if (st == SellerStatus.ACTIVE) {
                throw new ValidationException("This business is already selling on the marketplace.");
            }
            if (st == SellerStatus.SUSPENDED) {
                throw new ValidationException("Selling is suspended for this business. Contact MaxTheService support.");
            }
            before = s.getStatus();
            s.setUpdatedAt(now);
        }
        s.setDisplayName(name);
        s.setContactPhone(phone);
        s.setContactEmail(email);
        s.setCity(city);
        s.setPickupAddress(address);
        s.setServiceRadiusKm(radius);
        s.setStatus(SellerStatus.PENDING_REVIEW.name());
        s.setStatusReason(null);
        s.setAppliedBy(access.userId());
        s.setAppliedAt(now);
        s.setReviewedBy(null);
        s.setReviewedAt(null);
        try {
            s = profiles.saveAndFlush(s);
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(seller_organization_id): a second submit from the same business in the same instant.
            throw new DuplicateResourceException("This application was just submitted. Reload to see it.");
        }

        for (MarketPolicy p : current.values()) {
            if (!agreements.existsBySellerProfileIdAndPolicyId(s.getId(), p.getId())) {
                SellerAgreement a = new SellerAgreement();
                a.setSellerProfileId(s.getId());
                a.setSellerOrganizationId(org);
                a.setPolicyId(p.getId());
                a.setPolicyType(p.getPolicyType());
                a.setVersionNo(p.getVersionNo());
                a.setAcceptedBy(access.userId());
                a.setAcceptedAt(now);
                agreements.save(a);
            }
        }
        audit.record("MARKET_SELLER_APPLY", "SELLER_PROFILE", ref(s), org, before, s.getStatus(), name, null);
        return view(s);
    }

    /** The seller leaves the marketplace (or withdraws a pending application). Its own org only. */
    @Transactional
    public SellerView withdraw(String reason) {
        Long org = access.sellerOrg();
        SellerProfile s = profiles.findBySellerOrganizationId(org)
                .orElseThrow(() -> new ResourceNotFoundException("This business has not applied to sell."));
        if (s.statusEnum() == SellerStatus.SUSPENDED) {
            throw new ValidationException("Selling is suspended for this business. Contact MaxTheService support.");
        }
        String r = PolicyService.trimToNull(reason);
        if (r != null && r.length() > 255) throw new ValidationException("The reason can be at most 255 characters.");
        return move(s, SellerStatus.WITHDRAWN, r, "MARKET_SELLER_WITHDRAW");
    }

    // ── operator side ─────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SellerView> list(String status) {
        access.assertOperator();
        List<SellerProfile> rows;
        if (status == null || status.isBlank()) {
            rows = profiles.findAllByOrderByAppliedAtDesc();
        } else {
            SellerStatus st = parseStatus(status);
            rows = profiles.findByStatusOrderByAppliedAtAsc(st.name());
        }
        return rows.stream().map(this::view).toList();
    }

    @Transactional
    public SellerView approve(Long id) {
        access.assertOperator();
        SellerProfile s = find(id);
        Map<PolicyType, MarketPolicy> current = requiredCurrent();
        for (MarketPolicy p : current.values()) {
            if (!agreements.existsBySellerProfileIdAndPolicyId(s.getId(), p.getId())) {
                throw new ValidationException("This seller has not accepted \"" + p.getTitle() + "\" version "
                        + p.getVersionNo() + ", published after they applied. Ask them to re-apply.");
            }
        }
        return move(s, SellerStatus.ACTIVE, null, "MARKET_SELLER_APPROVE");
    }

    @Transactional
    public SellerView reject(Long id, String reason) {
        access.assertOperator();
        return move(find(id), SellerStatus.REJECTED, requireReason(reason), "MARKET_SELLER_REJECT");
    }

    @Transactional
    public SellerView suspend(Long id, String reason) {
        access.assertOperator();
        return move(find(id), SellerStatus.SUSPENDED, requireReason(reason), "MARKET_SELLER_SUSPEND");
    }

    @Transactional
    public SellerView reinstate(Long id) {
        access.assertOperator();
        return move(find(id), SellerStatus.ACTIVE, null, "MARKET_SELLER_REINSTATE");
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────────

    private SellerView move(SellerProfile s, SellerStatus next, String reason, String action) {
        SellerStatus from = s.statusEnum();
        if (from == null || !from.canMoveTo(next)) {
            throw new ValidationException("A seller that is " + from + " cannot be moved to " + next + ".");
        }
        s.setStatus(next.name());
        s.setStatusReason(reason);
        s.setReviewedBy(access.userId());
        s.setReviewedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        s = profiles.saveAndFlush(s);
        audit.record(action, "SELLER_PROFILE", ref(s), s.getSellerOrganizationId(), from.name(), next.name(),
                s.getDisplayName(), reason);
        return view(s);
    }

    /** The current published version of every type a seller must accept; refuses when one is missing. */
    private Map<PolicyType, MarketPolicy> requiredCurrent() {
        Map<PolicyType, MarketPolicy> all = policies.currentPublished();
        Map<PolicyType, MarketPolicy> out = new java.util.LinkedHashMap<>();
        for (PolicyType t : PolicyType.values()) {
            if (!t.sellerMustAccept()) continue;
            MarketPolicy p = all.get(t);
            if (p == null) {
                throw new ValidationException("The marketplace is not accepting sellers yet: no " + t.name()
                        + " policy has been published.");
            }
            out.put(t, p);
        }
        return out;
    }

    private SellerProfile find(Long id) {
        return profiles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Seller not found."));
    }

    private static SellerStatus parseStatus(String raw) {
        try {
            return SellerStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown seller status: " + raw);
        }
    }

    private static String requireReason(String reason) {
        String r = PolicyService.trimToNull(reason);
        if (r == null) throw new ValidationException("Give a reason — the seller will see it.");
        if (r.length() > 255) throw new ValidationException("The reason can be at most 255 characters.");
        return r;
    }

    private static String required(String v, String message, int max) {
        String t = PolicyService.trimToNull(v);
        if (t == null) throw new ValidationException(message);
        if (t.length() > max) throw new ValidationException(message + " (at most " + max + " characters)");
        return t;
    }

    private static String ref(SellerProfile s) { return "SELLER-" + s.getId(); }

    private SellerView view(SellerProfile s) {
        List<AgreementView> ags = agreements.findBySellerProfileIdOrderByAcceptedAtDesc(s.getId()).stream()
                .map(a -> new AgreementView(a.getPolicyType(), a.getVersionNo(), a.getPolicyId(), a.getAcceptedAt()))
                .toList();
        return new SellerView(s.getId(), s.getSellerOrganizationId(), s.getDisplayName(), s.getContactPhone(),
                s.getContactEmail(), s.getCity(), s.getPickupAddress(), s.getServiceRadiusKm(), s.getStatus(),
                s.getStatusReason(), s.getAppliedAt(), s.getReviewedAt(), ags);
    }
}
