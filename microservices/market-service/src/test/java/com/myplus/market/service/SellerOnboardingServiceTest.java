package com.myplus.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.market.dto.MarketDtos.ApplyRequest;
import com.myplus.market.dto.MarketDtos.SellerView;
import com.myplus.market.entity.MarketPolicy;
import com.myplus.market.entity.PolicyType;
import com.myplus.market.entity.SellerAgreement;
import com.myplus.market.entity.SellerProfile;
import com.myplus.market.entity.SellerStatus;
import com.myplus.market.repository.SellerAgreementRepo;
import com.myplus.market.repository.SellerProfileRepo;

/**
 * MP-0b — seller onboarding rules, without a database. The Flyway test covers the schema; this covers who may do
 * what and which versions an application binds the seller to.
 */
class SellerOnboardingServiceTest {

    private static final Set<String> ON = Set.of("marketplaceSelling");

    private SellerProfileRepo profiles;
    private SellerAgreementRepo agreements;
    private PolicyService policies;
    private MarketAuditService audit;
    private SellerOnboardingService svc;

    private final Map<Long, SellerProfile> byOrg = new java.util.HashMap<>();
    private final List<SellerAgreement> saved = new ArrayList<>();
    private Map<PolicyType, MarketPolicy> published;

    @BeforeEach
    void setUp() {
        profiles = mock(SellerProfileRepo.class);
        agreements = mock(SellerAgreementRepo.class);
        policies = mock(PolicyService.class);
        audit = mock(MarketAuditService.class);
        svc = new SellerOnboardingService(profiles, agreements, policies, new MarketAccess(TestIdentity.PLATFORM_ORG), audit);

        published = new EnumMap<>(PolicyType.class);
        long id = 100;
        for (PolicyType t : PolicyType.values()) published.put(t, policy(id++, t, 1));
        when(policies.currentPublished()).thenAnswer(i -> new EnumMap<>(published));

        when(profiles.findBySellerOrganizationId(anyLong())).thenAnswer(i -> Optional.ofNullable(byOrg.get(i.getArgument(0, Long.class))));
        when(profiles.findById(anyLong())).thenAnswer(i -> byOrg.values().stream()
                .filter(p -> p.getId().equals(i.getArgument(0, Long.class))).findFirst());
        when(profiles.saveAndFlush(any())).thenAnswer(i -> {
            SellerProfile p = i.getArgument(0);
            if (p.getId() == null) p.setId(500L + p.getSellerOrganizationId());
            byOrg.put(p.getSellerOrganizationId(), p);
            return p;
        });
        when(agreements.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
        when(agreements.existsBySellerProfileIdAndPolicyId(anyLong(), anyLong())).thenAnswer(i -> saved.stream()
                .anyMatch(a -> a.getSellerProfileId().equals(i.getArgument(0)) && a.getPolicyId().equals(i.getArgument(1))));
        when(agreements.findBySellerProfileIdOrderByAcceptedAtDesc(anyLong())).thenAnswer(i -> saved.stream()
                .filter(a -> a.getSellerProfileId().equals(i.getArgument(0))).toList());
    }

    @AfterEach
    void tearDown() { TestIdentity.clear(); }

    private static MarketPolicy policy(long id, PolicyType t, int version) {
        MarketPolicy p = new MarketPolicy();
        p.setId(id);
        p.setOrganizationId(TestIdentity.PLATFORM_ORG);
        p.setPolicyType(t.name());
        p.setVersionNo(version);
        p.setTitle(t.name() + " v" + version);
        p.setSummary("summary");
        p.setStatus("PUBLISHED");
        return p;
    }

    private List<Long> acceptAll() {
        return published.entrySet().stream().filter(e -> e.getKey().sellerMustAccept())
                .map(e -> e.getValue().getId()).toList();
    }

    private ApplyRequest req(List<Long> accepted) {
        return new ApplyRequest("Shahzad Mobile", "03001234567", "shop@example.com", "Karachi",
                "Shop 4, Saddar", 10, accepted);
    }

    @Test
    @DisplayName("capability OFF (and UNRESOLVED) → the application is refused and nothing is written")
    void off_refuses() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, Set.of("expenseManagement"));
        assertThatThrownBy(() -> svc.apply(req(acceptAll()))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("not switched on");
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, null);
        assertThatThrownBy(() -> svc.apply(req(acceptAll()))).isInstanceOf(ValidationException.class);
        verify(profiles, never()).saveAndFlush(any());
        verify(agreements, never()).save(any());
    }

    @Test
    @DisplayName("apply → PENDING_REVIEW for the caller's OWN org, with one agreement row per seller policy (4)")
    void apply_records_versions() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        SellerView v = svc.apply(req(acceptAll()));
        assertThat(v.status()).isEqualTo("PENDING_REVIEW");
        assertThat(v.sellerOrganizationId()).isEqualTo(TestIdentity.SELLER_ORG);
        assertThat(saved).hasSize(4).allMatch(a -> a.getSellerOrganizationId().equals(TestIdentity.SELLER_ORG));
        assertThat(saved).extracting(SellerAgreement::getPolicyType)
                .containsExactly("SELLER_AGREEMENT", "DATA_SHARING", "COMMISSION", "RETURNS_REFUNDS");
        verify(audit).record(eq("MARKET_SELLER_APPLY"), eq("SELLER_PROFILE"), any(), eq(TestIdentity.SELLER_ORG),
                eq(null), eq("PENDING_REVIEW"), any(), eq(null));
    }

    @Test
    @DisplayName("a missing acceptance names the document; a stale version id is the same refusal")
    void must_accept_current_versions() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        List<Long> missingCommission = acceptAll().stream()
                .filter(id -> !id.equals(published.get(PolicyType.COMMISSION).getId())).toList();
        assertThatThrownBy(() -> svc.apply(req(missingCommission))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("COMMISSION v1");
        // the operator publishes COMMISSION v2; the browser still holds v1's id
        List<Long> stale = acceptAll();
        published.put(PolicyType.COMMISSION, policy(900, PolicyType.COMMISSION, 2));
        assertThatThrownBy(() -> svc.apply(req(stale))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("version 2");
    }

    @Test
    @DisplayName("no published seller agreement yet → the marketplace is not accepting sellers")
    void not_open_yet() {
        published.remove(PolicyType.DATA_SHARING);
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        assertThatThrownBy(() -> svc.apply(req(acceptAll()))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("not accepting sellers yet");
    }

    @Test
    @DisplayName("the platform org itself cannot apply as a seller")
    void platform_cannot_apply() {
        TestIdentity.sellerOwner(TestIdentity.PLATFORM_ORG, ON);
        assertThatThrownBy(() -> svc.apply(req(acceptAll()))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("operator");
    }

    @Test
    @DisplayName("validation: radius 0 and 101 refused, blank shop name refused")
    void validation() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        assertThatThrownBy(() -> svc.apply(new ApplyRequest("S", "1", null, "K", "A", 0, acceptAll())))
                .hasMessageContaining("radius");
        assertThatThrownBy(() -> svc.apply(new ApplyRequest("S", "1", null, "K", "A", 101, acceptAll())))
                .hasMessageContaining("radius");
        assertThatThrownBy(() -> svc.apply(new ApplyRequest("  ", "1", null, "K", "A", 5, acceptAll())))
                .hasMessageContaining("shop name");
    }

    @Test
    @DisplayName("a tenant is not the operator: approve/reject/suspend/list refuse with 403")
    void tenant_cannot_decide() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));
        Long id = byOrg.get(TestIdentity.SELLER_ORG).getId();
        assertThatThrownBy(() -> svc.approve(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.reject(id, "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.suspend(id, "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.list(null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("operator approve → ACTIVE, audited against the SELLER's org; suspend needs a reason; reinstate")
    void operator_lifecycle() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));
        Long id = byOrg.get(TestIdentity.SELLER_ORG).getId();

        TestIdentity.operator();
        assertThat(svc.approve(id).status()).isEqualTo("ACTIVE");
        verify(audit).record(eq("MARKET_SELLER_APPROVE"), any(), any(), eq(TestIdentity.SELLER_ORG),
                eq("PENDING_REVIEW"), eq("ACTIVE"), any(), eq(null));
        assertThatThrownBy(() -> svc.suspend(id, " ")).hasMessageContaining("reason");
        assertThat(svc.suspend(id, "late deliveries").status()).isEqualTo("SUSPENDED");
        assertThat(svc.reinstate(id).status()).isEqualTo("ACTIVE");
        // an ACTIVE seller cannot be "approved" again or rejected
        assertThatThrownBy(() -> svc.reject(id, "x")).hasMessageContaining("cannot be moved");
    }

    @Test
    @DisplayName("a policy republished while the application waits blocks approval until the seller re-applies")
    void approval_rechecks_versions() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));
        Long id = byOrg.get(TestIdentity.SELLER_ORG).getId();
        published.put(PolicyType.SELLER_AGREEMENT, policy(901, PolicyType.SELLER_AGREEMENT, 2));

        TestIdentity.operator();
        assertThatThrownBy(() -> svc.approve(id)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("version 2");

        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));   // still PENDING_REVIEW; adds the v2 acceptance, keeps the v1 row
        assertThat(saved).filteredOn(a -> a.getPolicyType().equals("SELLER_AGREEMENT"))
                .extracting(SellerAgreement::getVersionNo).containsExactlyInAnyOrder(1, 2);
        TestIdentity.operator();
        assertThat(svc.approve(id).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("rejected → may re-apply; active / suspended → may not")
    void reapply_rules() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));
        Long id = byOrg.get(TestIdentity.SELLER_ORG).getId();
        TestIdentity.operator();
        svc.reject(id, "documents unclear");

        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        assertThat(svc.apply(req(acceptAll())).status()).isEqualTo("PENDING_REVIEW");

        TestIdentity.operator();
        svc.approve(id);
        svc.suspend(id, "complaints");
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        assertThatThrownBy(() -> svc.apply(req(acceptAll()))).hasMessageContaining("suspended");
        assertThat(byOrg.get(TestIdentity.SELLER_ORG).statusEnum()).isEqualTo(SellerStatus.SUSPENDED);
    }

    @Test
    @DisplayName("withdraw: active → WITHDRAWN → may apply again (to PENDING_REVIEW); suspended may not withdraw")
    void withdraw_and_return() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));
        Long id = byOrg.get(TestIdentity.SELLER_ORG).getId();
        TestIdentity.operator();
        svc.approve(id);

        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        assertThat(svc.withdraw("closing the shop").status()).isEqualTo("WITHDRAWN");
        assertThat(svc.apply(req(acceptAll())).status()).isEqualTo("PENDING_REVIEW");

        TestIdentity.operator();
        svc.approve(id);
        svc.suspend(id, "fraud check");
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        assertThatThrownBy(() -> svc.withdraw(null)).hasMessageContaining("suspended");
        // another business has nothing to withdraw
        TestIdentity.sellerOwner(TestIdentity.OTHER_SELLER_ORG, ON);
        assertThatThrownBy(() -> svc.withdraw(null)).hasMessageContaining("has not applied");
    }

    @Test
    @DisplayName("my profile is the caller's own — another seller sees null, never this one")
    void profile_is_own() {
        TestIdentity.sellerOwner(TestIdentity.SELLER_ORG, ON);
        svc.apply(req(acceptAll()));
        assertThat(svc.myProfile()).isNotNull();
        TestIdentity.sellerOwner(TestIdentity.OTHER_SELLER_ORG, ON);
        assertThat(svc.myProfile()).isNull();
    }
}
