package com.myplus.market.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.market.dto.MarketDtos.PolicyRequest;
import com.myplus.market.dto.MarketDtos.PolicyView;
import com.myplus.market.entity.MarketPolicy;
import com.myplus.market.entity.PolicyStatus;
import com.myplus.market.entity.PolicyType;
import com.myplus.market.repository.MarketPolicyRepo;

import lombok.RequiredArgsConstructor;

/**
 * Versioned marketplace policies (MP-0b). A published version is never edited — a change is a new version, so
 * an order or an agreement that pointed at version N keeps meaning what it meant (source design §13 snapshots).
 */
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final MarketPolicyRepo repo;
    private final MarketAccess access;
    private final MarketAuditService audit;

    @Transactional(readOnly = true)
    public List<PolicyView> list(String type) {
        access.assertOperator();
        Long org = access.platformOrg();
        List<MarketPolicy> rows;
        if (type == null || type.isBlank()) {
            rows = repo.findByOrganizationIdOrderByPolicyTypeAscVersionNoDesc(org);
        } else {
            PolicyType t = requireType(type);
            rows = repo.findByOrganizationIdAndPolicyTypeOrderByVersionNoDesc(org, t.name());
        }
        return rows.stream().map(PolicyService::view).toList();
    }

    @Transactional
    public PolicyView create(PolicyRequest r) {
        access.assertOperator();
        if (r == null) throw new ValidationException("Policy details are required.");
        PolicyType type = requireType(r.policyType());
        String title = trimToNull(r.title());
        String summary = trimToNull(r.summary());
        if (title == null) throw new ValidationException("Give the policy a title.");
        if (title.length() > 160) throw new ValidationException("The title can be at most 160 characters.");
        if (summary == null) throw new ValidationException("Write the policy summary a seller or customer will read.");
        if (summary.length() > 2000) throw new ValidationException("The summary can be at most 2000 characters.");
        String url = trimToNull(r.documentUrl());
        if (url != null && (url.length() > 500 || !url.startsWith("https://"))) {
            throw new ValidationException("The document link must be an https:// address of at most 500 characters.");
        }

        Long org = access.platformOrg();
        MarketPolicy p = new MarketPolicy();
        p.setOrganizationId(org);
        p.setPolicyType(type.name());
        p.setVersionNo(repo.maxVersion(org, type.name()) + 1);
        p.setTitle(title);
        p.setSummary(summary);
        p.setDocumentUrl(url);
        p.setEffectiveFrom(r.effectiveFrom());
        p.setStatus(PolicyStatus.DRAFT.name());
        p.setCreatedBy(access.userId());
        p.setCreatedAt(LocalDateTime.now());
        try {
            p = repo.saveAndFlush(p);
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(org, type, version_no): another operator created the same version number at the same moment.
            throw new DuplicateResourceException("Another version of this policy was saved at the same moment. "
                    + "Reload and try again.");
        }
        audit.record("MARKET_POLICY_CREATE", "MARKET_POLICY", ref(p), org, null, PolicyStatus.DRAFT.name(),
                type.name() + " v" + p.getVersionNo() + " — " + title, null);
        return view(p);
    }

    /**
     * DRAFT → PUBLISHED; the previously published version of the same type → SUPERSEDED, in one transaction.
     * Serialised on a lock of the current published row; the UNIQUE (org, published_slot) key is the backstop
     * when there is no current row to lock (the first publish of a type).
     */
    @Transactional
    public PolicyView publish(Long id) {
        access.assertOperator();
        Long org = access.platformOrg();
        MarketPolicy p = repo.findByIdAndOrganizationId(id, org)
                .orElseThrow(() -> new ResourceNotFoundException("Policy not found."));
        if (!PolicyStatus.DRAFT.name().equals(p.getStatus())) {
            throw new ValidationException("Only a draft can be published; this version is " + p.getStatus() + ".");
        }
        MarketPolicy old = repo.lockPublished(org, p.getPolicyType()).orElse(null);
        if (old != null) {
            old.setStatus(PolicyStatus.SUPERSEDED.name());
            old.setPublishedSlot(null);
            repo.saveAndFlush(old);
            audit.record("MARKET_POLICY_SUPERSEDE", "MARKET_POLICY", ref(old), org, PolicyStatus.PUBLISHED.name(),
                    PolicyStatus.SUPERSEDED.name(), old.getPolicyType() + " v" + old.getVersionNo()
                            + " replaced by v" + p.getVersionNo(), null);
        }
        p.setStatus(PolicyStatus.PUBLISHED.name());
        p.setPublishedSlot(p.getPolicyType());
        p.setPublishedAt(LocalDateTime.now());
        p.setPublishedBy(access.userId());
        try {
            p = repo.saveAndFlush(p);
        } catch (DataIntegrityViolationException race) {
            throw new DuplicateResourceException("Another version of this policy was published at the same moment. "
                    + "Reload and try again.");
        }
        audit.record("MARKET_POLICY_PUBLISH", "MARKET_POLICY", ref(p), org, PolicyStatus.DRAFT.name(),
                PolicyStatus.PUBLISHED.name(), p.getPolicyType() + " v" + p.getVersionNo(), null);
        return view(p);
    }

    /** The published version of each type, in the enum's order. Read by sellers (what they must accept). */
    @Transactional(readOnly = true)
    public Map<PolicyType, MarketPolicy> currentPublished() {
        Map<PolicyType, MarketPolicy> out = new LinkedHashMap<>();
        List<MarketPolicy> published = repo.findByOrganizationIdAndStatus(access.platformOrg(),
                PolicyStatus.PUBLISHED.name());
        for (PolicyType t : PolicyType.values()) {
            published.stream().filter(p -> t.name().equals(p.getPolicyType())).findFirst()
                    .ifPresent(p -> out.put(t, p));
        }
        return out;
    }

    public static PolicyView view(MarketPolicy p) {
        PolicyType t = PolicyType.parse(p.getPolicyType());
        return new PolicyView(p.getId(), p.getPolicyType(), p.getVersionNo(), p.getTitle(), p.getSummary(),
                p.getDocumentUrl(), p.getStatus(), p.getEffectiveFrom(), p.getPublishedAt(),
                t != null && t.sellerMustAccept());
    }

    static PolicyType requireType(String raw) {
        PolicyType t = PolicyType.parse(raw);
        if (t == null) {
            throw new ValidationException("Unknown policy type. Use one of: " + Arrays.toString(PolicyType.values()));
        }
        return t;
    }

    private static String ref(MarketPolicy p) { return "POL-" + p.getId(); }

    static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
