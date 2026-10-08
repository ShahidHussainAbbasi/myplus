package com.myplus.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.myplus.catalog.entity.PriceChangeRequest;
import com.myplus.catalog.entity.Product;
import com.myplus.catalog.entity.ProductPriceHistory;
import com.myplus.catalog.repository.PriceChangeRequestRepository;
import com.myplus.catalog.repository.ProductRepository;
import com.myplus.catalog.support.TestTenant;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** PR-4 — the approval queue: propose, supersede, approve (with the moved-price refusal), reject, tenant scope. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriceApprovalServiceTest {

    private static final Long ORG = 7L, USER = 3L, PRODUCT = 42L;

    @Mock private PriceChangeRequestRepository requests;
    @Mock private ProductRepository productRepository;
    @Mock private ProductService productService;

    private PriceApprovalService service;
    private Product product;

    @BeforeEach
    void setUp() {
        TestTenant.authenticate(ORG, USER);
        service = new PriceApprovalService(requests, productRepository, productService);
        product = new Product();
        product.setId(PRODUCT);
        product.setName("abc-123");
        product.setOrganizationId(ORG);
        product.setSellingPrice(new BigDecimal("200.00"));
        when(productService.getEntity(PRODUCT)).thenReturn(product);
        when(requests.save(any(PriceChangeRequest.class))).thenAnswer(i -> {
            PriceChangeRequest r = i.getArgument(0);
            if (r.getId() == null) r.setId(900L);
            return r;
        });
    }

    @AfterEach
    void tearDown() { TestTenant.clear(); }

    private PriceChangeRequest pending(long id, String proposed) {
        PriceChangeRequest r = new PriceChangeRequest();
        r.setId(id);
        r.setOrganizationId(ORG);
        r.setProductId(PRODUCT);
        r.setCurrentPrice(new BigDecimal("200.00"));
        r.setProposedPrice(new BigDecimal(proposed));
        r.setSource(ProductPriceHistory.MARKUP);
        r.setReason(PriceChangeRequest.REASON_APPROVAL);
        r.setRef("BILL-1");
        r.setStatus(PriceChangeRequest.PENDING);
        return r;
    }

    @Test
    @DisplayName("a proposal records the price now, the proposed price, why and the bill — PENDING, by this user")
    void proposeRecordsAPendingChange() {
        when(requests.findPendingForProduct(PRODUCT, ORG)).thenReturn(List.of());

        Map<String, Object> v = service.propose(PRODUCT, new BigDecimal("240.45"), new BigDecimal("210"), "MARKUP",
                "APPROVAL", "14.5% on cost, the business rate", "BILL-1");

        ArgumentCaptor<PriceChangeRequest> c = ArgumentCaptor.forClass(PriceChangeRequest.class);
        verify(requests).save(c.capture());
        PriceChangeRequest r = c.getValue();
        assertThat(r.getStatus()).isEqualTo(PriceChangeRequest.PENDING);
        assertThat(r.getCurrentPrice()).isEqualByComparingTo("200.00");
        assertThat(r.getProposedPrice()).isEqualByComparingTo("240.45");
        assertThat(r.getOrganizationId()).isEqualTo(ORG);
        assertThat(r.getProposedBy()).isEqualTo(USER);
        assertThat(r.getRef()).isEqualTo("BILL-1");
        assertThat(v.get("changePct")).isEqualTo(new BigDecimal("20.2"));
        assertThat(v.get("productName")).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("a newer proposal SUPERSEDES the pending one for the same product")
    void aNewerProposalSupersedes() {
        PriceChangeRequest older = pending(5, "240.45");
        when(requests.findPendingForProduct(PRODUCT, ORG)).thenReturn(List.of(older));

        service.propose(PRODUCT, new BigDecimal("251.90"), new BigDecimal("220"), "MARKUP", "APPROVAL", null, "BILL-2");

        assertThat(older.getStatus()).isEqualTo(PriceChangeRequest.SUPERSEDED);
        assertThat(older.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("a proposal equal to the price now is nothing to decide — no row")
    void sameAsNowIsNothing() {
        assertThat(service.propose(PRODUCT, new BigDecimal("200"), null, "PURCHASE", "APPROVAL", null, "B")).isNull();
        verify(requests, never()).save(any());
    }

    @Test
    @DisplayName("⭐ approve sets the price through the purchase path, source APPROVAL, naming the bill")
    void approveSetsThePrice() {
        PriceChangeRequest r = pending(5, "240.45");
        when(requests.findScoped(5L, ORG)).thenReturn(Optional.of(r));

        service.approve(5L, new BigDecimal("200"));

        verify(productService).updatePrice(eq(PRODUCT), eq(new BigDecimal("240.45")), eq(null), eq("BILL-1"),
                eq(ProductPriceHistory.APPROVAL));
        assertThat(r.getStatus()).isEqualTo(PriceChangeRequest.APPROVED);
        assertThat(r.getDecidedBy()).isEqualTo(USER);
    }

    @Test
    @DisplayName("⭐ approve against a price that moved since is REFUSED and changes nothing")
    void approveRefusesAMovedPrice() {
        PriceChangeRequest r = pending(5, "240.45");
        when(requests.findScoped(5L, ORG)).thenReturn(Optional.of(r));
        product.setSellingPrice(new BigDecimal("205.00"));   // re-priced by hand meanwhile

        assertThatThrownBy(() -> service.approve(5L, new BigDecimal("200")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("The price is now 205.00");
        verify(productService, never()).updatePrice(anyLong(), any(), any(), any(), any());
        assertThat(r.getStatus()).isEqualTo(PriceChangeRequest.PENDING);
    }

    @Test
    @DisplayName("a decided change is final: approving it again is refused")
    void decidedIsFinal() {
        PriceChangeRequest r = pending(5, "240.45");
        r.setStatus(PriceChangeRequest.REJECTED);
        when(requests.findScoped(5L, ORG)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.approve(5L, null)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("already decided");
    }

    @Test
    @DisplayName("reject records the decision and its note; the price is untouched")
    void rejectChangesNothing() {
        PriceChangeRequest r = pending(5, "240.45");
        when(requests.findScoped(5L, ORG)).thenReturn(Optional.of(r));

        service.reject(5L, "  too high for this street  ");

        assertThat(r.getStatus()).isEqualTo(PriceChangeRequest.REJECTED);
        assertThat(r.getDecisionNote()).isEqualTo("too high for this street");
        verify(productService, never()).updatePrice(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("another tenant's proposal is not found (the read is scoped to the caller's organisation)")
    void anotherTenantIsNotFound() {
        when(requests.findScoped(77L, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(77L, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reject(77L, null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an unknown status filter is refused rather than read as 'all'")
    void unknownStatusIsRefused() {
        assertThatThrownBy(() -> service.list("pendingg")).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("change % — +20.2 for 200 → 240.45, −10.0 for 300 → 270, null with no price")
    void changePct() {
        assertThat(PriceApprovalService.changePct(new BigDecimal("200"), new BigDecimal("240.45"))).isEqualByComparingTo("20.2");
        assertThat(PriceApprovalService.changePct(new BigDecimal("300"), new BigDecimal("270"))).isEqualByComparingTo("-10.0");
        assertThat(PriceApprovalService.changePct(null, new BigDecimal("1"))).isNull();
    }
}
