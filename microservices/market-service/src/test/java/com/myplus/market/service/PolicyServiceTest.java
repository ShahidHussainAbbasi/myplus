package com.myplus.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.DuplicateResourceException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.market.dto.MarketDtos.PolicyRequest;
import com.myplus.market.dto.MarketDtos.PolicyView;
import com.myplus.market.entity.MarketPolicy;
import com.myplus.market.repository.MarketPolicyRepo;

class PolicyServiceTest {

    private MarketPolicyRepo repo;
    private MarketAuditService audit;
    private PolicyService svc;

    @BeforeEach
    void setUp() {
        repo = mock(MarketPolicyRepo.class);
        audit = mock(MarketAuditService.class);
        svc = new PolicyService(repo, new MarketAccess(TestIdentity.PLATFORM_ORG), audit);
        when(repo.saveAndFlush(any())).thenAnswer(i -> {
            MarketPolicy p = i.getArgument(0);
            if (p.getId() == null) p.setId(77L);
            return p;
        });
    }

    @AfterEach
    void tearDown() { TestIdentity.clear(); }

    private static PolicyRequest req(String type) {
        return new PolicyRequest(type, "Seller agreement", "What a seller agrees to.", "https://docs.example/sa", null);
    }

    @Test
    @DisplayName("a tenant owner (ADMIN_PRIVILEGE) is not the operator")
    void tenant_refused() {
        TestIdentity.sellerOwner(13L, java.util.Set.of("marketplaceSelling"));
        assertThatThrownBy(() -> svc.create(req("SELLER_AGREEMENT"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.publish(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("create → DRAFT, next version number, owned by the PLATFORM org (not the caller's request)")
    void create_draft() {
        TestIdentity.operator();
        when(repo.maxVersion(1L, "SELLER_AGREEMENT")).thenReturn(2);
        PolicyView v = svc.create(req("seller_agreement"));
        assertThat(v.status()).isEqualTo("DRAFT");
        assertThat(v.versionNo()).isEqualTo(3);
        assertThat(v.sellerMustAccept()).isTrue();
        verify(repo).saveAndFlush(argThat(p -> p.getOrganizationId().equals(1L)));
    }

    @Test
    @DisplayName("unknown type, blank title, http:// link → refused with a sentence")
    void validation() {
        TestIdentity.operator();
        assertThatThrownBy(() -> svc.create(req("LOYALTY"))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unknown policy type");
        assertThatThrownBy(() -> svc.create(new PolicyRequest("COD", " ", "s", null, null)))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> svc.create(new PolicyRequest("COD", "t", "s", "http://x", null)))
                .hasMessageContaining("https://");
    }

    @Test
    @DisplayName("two operators creating the same version at once → 409, not a 500")
    void version_race() {
        TestIdentity.operator();
        doThrow(new DataIntegrityViolationException("uq_market_policy_version")).when(repo).saveAndFlush(any());
        assertThatThrownBy(() -> svc.create(req("COD"))).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("publish v2 supersedes v1 in the same transaction; the old slot is cleared BEFORE the new is set")
    void publish_supersedes() {
        TestIdentity.operator();
        MarketPolicy v1 = row(10L, 1, "PUBLISHED");
        v1.setPublishedSlot("COMMISSION");
        MarketPolicy v2 = row(11L, 2, "DRAFT");
        when(repo.findByIdAndOrganizationId(11L, 1L)).thenReturn(Optional.of(v2));
        when(repo.lockPublished(1L, "COMMISSION")).thenReturn(Optional.of(v1));
        var order = inOrder(repo);

        PolicyView out = svc.publish(11L);

        assertThat(out.status()).isEqualTo("PUBLISHED");
        assertThat(v1.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(v1.getPublishedSlot()).isNull();
        assertThat(v2.getPublishedSlot()).isEqualTo("COMMISSION");
        order.verify(repo).saveAndFlush(v1);
        order.verify(repo).saveAndFlush(v2);
        verify(audit).record(eq("MARKET_POLICY_SUPERSEDE"), anyString(), anyString(), eq(1L), any(), any(), any(), any());
        verify(audit).record(eq("MARKET_POLICY_PUBLISH"), anyString(), anyString(), eq(1L), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a published or superseded version cannot be published again")
    void only_drafts() {
        TestIdentity.operator();
        when(repo.findByIdAndOrganizationId(anyLong(), eq(1L))).thenReturn(Optional.of(row(12L, 1, "SUPERSEDED")));
        assertThatThrownBy(() -> svc.publish(12L)).hasMessageContaining("Only a draft");
    }

    private static MarketPolicy row(Long id, int v, String status) {
        MarketPolicy p = new MarketPolicy();
        p.setId(id);
        p.setOrganizationId(1L);
        p.setPolicyType("COMMISSION");
        p.setVersionNo(v);
        p.setTitle("Commission v" + v);
        p.setSummary("s");
        p.setStatus(status);
        return p;
    }
}
