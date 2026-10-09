package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;

/** MKT-1c — offers: stamped parties, guards, price limits, the approval lifecycle. In-memory repositories. */
@ExtendWith(MockitoExtension.class)
class MarketplaceOfferServiceTest {

    static final long SELLER_A = 7L, SELLER_B = 8L, PHONE = 100L, RX = 101L, WARRANTY = 1L, RETURN = 2L, COMMISSION = 3L;

    @Mock MarketplaceOfferRepository offers;
    @Mock MarketplaceProductRepository products;
    @Mock MarketplaceProductSourceRepository sources;
    @Mock MarketplaceSellerService sellers;
    @Mock MarketplacePolicyService policies;
    @Mock OfferProjectionService projection;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;                              // G-16: actions are audited
    @Mock MarketplaceSettingsService settings;                        // MKT-3a: which org is the warehouse (none by default)
    @InjectMocks MarketplaceOfferService service;

    final Map<Long, MarketplaceOffer> offerRows = new HashMap<>();
    long org = SELLER_A;
    long seq;

    @BeforeEach
    void wire() {
        lenient().when(access.org()).thenAnswer(i -> org);
        lenient().when(access.userId()).thenReturn(11L);
        lenient().when(products.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(product(i.getArgument(0))));
        lenient().when(sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(anyLong(), anyLong(), eq("MATCHED")))
                .thenAnswer(i -> (Long) i.getArgument(0) == SELLER_A || (Long) i.getArgument(0) == SELLER_B
                        ? Optional.of(source(i.getArgument(0), i.getArgument(1))) : Optional.empty());
        lenient().when(offers.findByIdAndOrganizationId(anyLong(), anyLong())).thenAnswer(i -> Optional.ofNullable(
                offerRows.get(i.<Long>getArgument(0))).filter(o -> o.getOrganizationId().equals(i.getArgument(1))));
        lenient().when(offers.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(offerRows.get(i.<Long>getArgument(0))));
        lenient().when(offers.findByOrganizationIdAndMktProductId(anyLong(), anyLong())).thenAnswer(i -> offerRows.values()
                .stream().filter(o -> o.getOrganizationId().equals(i.getArgument(0)) && o.getMktProductId().equals(i.getArgument(1))).findFirst());
        lenient().when(offers.save(any())).thenAnswer(i -> {
            MarketplaceOffer o = i.getArgument(0);
            if (o.getId() == null) o.setId(++seq);
            o.setVersion(o.getVersion() == null ? 0 : o.getVersion() + 1);
            offerRows.put(o.getId(), o);
            return o;
        });
        lenient().when(policies.usable(eq(WARRANTY), anyString())).thenReturn(policy(WARRANTY));
        lenient().when(policies.usable(eq(RETURN), anyString())).thenReturn(policy(RETURN));
        lenient().when(policies.defaultCommission()).thenReturn(policy(COMMISSION));
    }

    MarketplaceProduct product(long id) {
        if (id != PHONE && id != RX) return null;
        MarketplaceProduct p = new MarketplaceProduct();
        p.setId(id);
        p.setCanonicalName(id == PHONE ? "Samsung Galaxy A32 128GB Black" : "Panadol");
        p.setApprovalStatus("APPROVED");
        p.setRegulatedStatus(id == PHONE ? "NONE" : "PRESCRIPTION");
        p.setPriceCeiling(id == PHONE ? new BigDecimal("60000") : null);
        return p;
    }

    static MarketplaceProductSource source(long org, long product) {
        MarketplaceProductSource s = new MarketplaceProductSource();
        s.setOrganizationId(org);
        s.setMktProductId(product);
        s.setSourceProductId(org * 1000 + product);
        s.setMatchStatus("MATCHED");
        return s;
    }

    static MarketplacePolicy policy(long id) {
        MarketplacePolicy p = new MarketplacePolicy();
        p.setId(id);
        return p;
    }

    static OfferDTOs.SaveRequest offer(Long id, String price) {
        return new OfferDTOs.SaveRequest(id, id == null ? PHONE : null, null, null, null,
                price == null ? null : new BigDecimal(price), "Karachi, karachi ,Lahore", 4, WARRANTY, RETURN, null, null);
    }

    OfferDTOs.Offer approved() {
        OfferDTOs.Offer o = service.save(offer(null, "52000"));
        service.submit(o.id());
        return service.decide(o.id(), new OfferDTOs.DecisionRequest("APPROVE", null, null));
    }

    @Test
    @DisplayName("[MKT-R3.1] [MKT-R4.1] [MKT-R22.1] the four parties are stamped from the token; MERCHANT = the seller is all four")
    void partiesStamped() {
        OfferDTOs.Offer o = service.save(offer(null, "52000"));
        assertThat(o.organizationId()).isEqualTo(SELLER_A);
        assertThat(o.sellerOrganizationId()).isEqualTo(SELLER_A);
        assertThat(o.stockOwnerOrganizationId()).isEqualTo(SELLER_A);
        assertThat(o.custodianOrganizationId()).isEqualTo(SELLER_A);
        assertThat(o.fulfillerOrganizationId()).isEqualTo(SELLER_A);
        assertThat(o.stockSourceType()).isEqualTo("MERCHANT");
        assertThat(o.sourceProductId()).as("stock is read for the seller's MATCHED catalogue product").isEqualTo(7100L);
        assertThat(o.approvalStatus()).isEqualTo("DRAFT");
        assertThat(o.deliveryAreas()).isEqualTo("Karachi,Lahore");
    }

    @Test
    @DisplayName("[MKT-R5.3] a seller with no MATCHED source for the product cannot offer it")
    void needsMatchedSource() {
        org = 99L;
        assertThatThrownBy(() -> service.save(offer(null, "52000"))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("wait for MaxTheService to match it");
    }

    @Test
    @DisplayName("[MKT-R20.2] [MKT-R4.4] Phase 1: supplier and consignment stock are refused, and so is a regulated product")
    void phaseOne() {
        assertThatThrownBy(() -> service.save(new OfferDTOs.SaveRequest(null, PHONE, "SUPPLIER", null, null,
                new BigDecimal("52000"), "Karachi", 4, null, null, null, null))).hasMessageContaining("supplier");
        assertThatThrownBy(() -> service.save(new OfferDTOs.SaveRequest(null, RX, null, null, null,
                new BigDecimal("500"), "Karachi", 4, null, null, null, null)))
                .hasMessage("Prescription and restricted products cannot be sold on the marketplace yet.");
    }

    @Test
    @DisplayName("[MKT-R7.4] [MKT-R7.5] the seller sets the price, inside the operator's limits")
    void priceLimits() {
        assertThatThrownBy(() -> service.save(offer(null, "75000")))
                .hasMessageContaining("price is outside the allowed range").hasMessageContaining("maximum Rs. 60000");
        assertThatThrownBy(() -> service.save(offer(null, "0"))).hasMessageContaining("above zero");
    }

    @Test
    @DisplayName("[MKT-R14.1] an offer cannot go for approval without a warranty and a return policy")
    void policiesRequired() {
        OfferDTOs.Offer o = service.save(new OfferDTOs.SaveRequest(null, PHONE, null, null, null,
                new BigDecimal("52000"), "Karachi", 4, null, null, null, null));
        assertThatThrownBy(() -> service.submit(o.id())).hasMessageContaining("warranty and a return policy");
    }

    @Test
    @DisplayName("[MKT-R7.4] approval stamps the operator's default commission; the seller never chooses it")
    void commissionStamped() {
        assertThat(approved().commissionPolicyId()).isEqualTo(COMMISSION);
        // with no default commission policy, nothing can be approved — an offer must never go live untaxed by us
        org = SELLER_B;
        OfferDTOs.Offer o = service.save(offer(null, "51500"));
        service.submit(o.id());
        when(policies.defaultCommission()).thenThrow(new ValidationException("Set a default commission policy before approving offers."));
        assertThatThrownBy(() -> service.decide(o.id(), new OfferDTOs.DecisionRequest("APPROVE", null, null)))
                .hasMessageContaining("default commission policy");
    }

    @Test
    @DisplayName("[MKT-R19.1] [MKT-R7.6] reject and suspend need a reason; illegal moves are refused in words")
    void lifecycle() {
        OfferDTOs.Offer o = service.save(offer(null, "52000"));
        assertThatThrownBy(() -> service.decide(o.id(), new OfferDTOs.DecisionRequest("APPROVE", null, null)))
                .hasMessage("An offer that is draft cannot be moved to approved.");
        service.submit(o.id());
        assertThatThrownBy(() -> service.decide(o.id(), new OfferDTOs.DecisionRequest("REJECT", " ", null)))
                .hasMessageContaining("Give the seller a reason");
        assertThat(service.decide(o.id(), new OfferDTOs.DecisionRequest("REJECT", "photo missing", null)).reviewNote())
                .isEqualTo("photo missing");
        service.submit(o.id());
        service.decide(o.id(), new OfferDTOs.DecisionRequest("APPROVE", null, null));
        assertThat(service.decide(o.id(), new OfferDTOs.DecisionRequest("SUSPEND", "complaints", null)).approvalStatus())
                .isEqualTo("SUSPENDED");
        assertThat(service.decide(o.id(), new OfferDTOs.DecisionRequest("REINSTATE", null, null)).approvalStatus())
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("[MKT-R7.5] the seller pauses only a live offer, and every write re-publishes the projection")
    void pauseAndPublish() {
        OfferDTOs.Offer draft = service.save(offer(null, "52000"));
        assertThatThrownBy(() -> service.save(new OfferDTOs.SaveRequest(draft.id(), null, null, null, null, null, null,
                null, null, null, true, null))).hasMessageContaining("Only a live offer can be paused");
        service.submit(draft.id());
        OfferDTOs.Offer live = service.decide(draft.id(), new OfferDTOs.DecisionRequest("APPROVE", null, null));
        assertThat(service.save(new OfferDTOs.SaveRequest(live.id(), null, null, null, null, null, null, null, null,
                null, true, null)).paused()).isTrue();
        verify(projection, atLeastOnce()).publish(any());
    }

    @Test
    @DisplayName("[MKT-R22.1] another seller's offer id reads as 'No such offer'; the operator API refuses a tenant")
    void tenancy() {
        OfferDTOs.Offer mine = service.save(offer(null, "52000"));
        org = SELLER_B;
        assertThatThrownBy(() -> service.one(mine.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.save(offer(mine.id(), "1"))).isInstanceOf(ResourceNotFoundException.class);
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.decide(mine.id(), new OfferDTOs.DecisionRequest("APPROVE", null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(offerRows.get(mine.id()).getApprovalStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("[MKT-R5.3] one offer per seller per product in Phase 1; an inactive seller writes nothing")
    void oneOfferAndActiveSeller() {
        service.save(offer(null, "52000"));
        assertThatThrownBy(() -> service.save(offer(null, "51000"))).hasMessageContaining("already have an offer");
        doThrow(new ValidationException("MaxTheService is still reviewing your seller account.")).when(sellers).assertActiveSeller();
        assertThatThrownBy(() -> service.save(offer(null, "52000"))).hasMessageContaining("still reviewing");
        verify(offers, never()).findByOrganizationIdAndMktProductId(eq(99L), anyLong());
    }

    @Test
    @DisplayName("[MKT-R7.4] the operator's price limits: floor above ceiling is refused")
    void limits() {
        assertThatThrownBy(() -> service.setLimits(PHONE, new OfferDTOs.LimitsRequest(new BigDecimal("5"), new BigDecimal("1"))))
                .hasMessageContaining("floor cannot be above the ceiling");
    }

    @Test
    @DisplayName("[MKT-R7.4] offers waiting for review are worked oldest first; a decided list shows the newest first")
    void queueOrder() {
        when(offers.findByApprovalStatusOrderByCreatedAtAsc(anyString(), any()))
                .thenReturn(Page.empty());
        when(offers.findByApprovalStatusOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Page.empty());
        service.queue(null, 0, 10);
        service.queue("approved", 0, 10);
        verify(offers).findByApprovalStatusOrderByCreatedAtAsc(eq("PENDING_REVIEW"), any());
        verify(offers).findByApprovalStatusOrderByCreatedAtDesc(eq("APPROVED"), any());
    }

    @Test
    @DisplayName("[MKT-R4.2] [MKT-R22.1] the warehouse's offers are PLATFORM stock (owner MaxTheService); no other seller can claim it")
    void platformStock() {
        org = SELLER_B;
        assertThatThrownBy(() -> service.save(new OfferDTOs.SaveRequest(null, PHONE, "PLATFORM", null, null,
                new BigDecimal("52000"), "Karachi", 4, WARRANTY, RETURN, null, null)))
                .hasMessage("Only the MaxTheService warehouse sells MaxTheService's own stock.");
        org = SELLER_A;
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(SELLER_A));
        // even when it asks for MERCHANT: the source follows who lists it
        OfferDTOs.Offer o = service.save(new OfferDTOs.SaveRequest(null, PHONE, "MERCHANT", null, null,
                new BigDecimal("52000"), "Karachi", 4, WARRANTY, RETURN, null, null));
        assertThat(offerRows.get(o.id()).getStockSourceType()).isEqualTo("PLATFORM");
        assertThat(offerRows.get(o.id()).getStockOwnerOrganizationId()).isEqualTo(SELLER_A);
    }
}
