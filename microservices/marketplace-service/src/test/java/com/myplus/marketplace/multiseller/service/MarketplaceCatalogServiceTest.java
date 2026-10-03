package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.CatalogDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;

/**
 * MKT-1b — product proposals and match review. Repositories are in-memory maps; catalog-service answers per
 * tenant the way its scoped query does (another tenant's product is simply absent).
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceCatalogServiceTest {

    static final long SELLER_A = 7L, SELLER_B = 8L;

    @Mock MarketplaceProductRepository products;
    @Mock MarketplaceProductSourceRepository sources;
    @Mock MarketplaceSellerService sellers;
    @Mock SellerAccess access;
    @Mock CatalogClient catalog;
    @InjectMocks MarketplaceCatalogService service;

    final Map<Long, MarketplaceProduct> productRows = new HashMap<>();
    final Map<Long, MarketplaceProductSource> sourceRows = new HashMap<>();
    /** what each tenant's catalogue holds: org → product id → ref */
    final Map<Long, Map<Long, ProductRef>> catalogs = new HashMap<>();
    long org = SELLER_A;
    long seq = 0;

    @BeforeEach
    void wire() {
        lenient().when(access.org()).thenAnswer(i -> org);
        lenient().when(access.userId()).thenReturn(11L);
        lenient().when(catalog.getProductsFresh(anyList(), anyBoolean())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0);
            Map<Long, ProductRef> mine = catalogs.getOrDefault(org, Map.of());
            List<ProductRef> out = new ArrayList<>();
            for (Long id : ids) if (mine.containsKey(id)) out.add(mine.get(id));
            return out;
        });
        lenient().when(sources.findByOrganizationIdAndSourceProductId(anyLong(), anyLong())).thenAnswer(i ->
                sourceRows.values().stream().filter(s -> s.getOrganizationId().equals(i.getArgument(0))
                        && s.getSourceProductId().equals(i.getArgument(1))).findFirst());
        lenient().when(sources.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(sourceRows.get(i.<Long>getArgument(0))));
        lenient().when(sources.save(any())).thenAnswer(i -> {
            MarketplaceProductSource s = i.getArgument(0);
            if (s.getId() == null) s.setId(++seq);
            s.setVersion(s.getVersion() == null ? 0 : s.getVersion() + 1);
            sourceRows.put(s.getId(), s);
            return s;
        });
        lenient().when(products.findByIdentityKey(anyString())).thenAnswer(i -> productRows.values().stream()
                .filter(p -> p.getIdentityKey().equals(i.getArgument(0))).findFirst());
        lenient().when(products.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(productRows.get(i.<Long>getArgument(0))));
        lenient().when(products.saveAndFlush(any())).thenAnswer(i -> {
            MarketplaceProduct p = i.getArgument(0);
            if (p.getId() == null) p.setId(100 + (long) productRows.size());
            productRows.put(p.getId(), p);
            return p;
        });
        stock(SELLER_A, 1L, "Galaxy A32 128 Black", false, false);
        stock(SELLER_A, 2L, "Panadol", true, false);
        stock(SELLER_B, 5L, "Samsung A-32 128GB blk", false, false);
        stock(SELLER_B, 6L, "Galaxy A32 64 Black", false, false);
    }

    void stock(long tenant, long id, String name, boolean rx, boolean controlled) {
        catalogs.computeIfAbsent(tenant, k -> new HashMap<>()).put(id, ProductRef.builder().id(id).name(name)
                .rxRequired(rx).controlledSubstance(controlled).build());
    }

    static CatalogDTOs.ProposeRequest a32(long productId, String storage) {
        return new CatalogDTOs.ProposeRequest(productId, "Samsung", "Galaxy A32", storage, "Black", null, null, null,
                "New", "12M", null);
    }

    static CatalogDTOs.DecisionRequest decision(String d, Long product, String note) {
        return new CatalogDTOs.DecisionRequest(d, product, note, null);
    }

    @Test
    @DisplayName("[MKT-R6.2] [MKT-R6.4] [MKT-R5.1] the key is computed on the SERVER and the proposal waits for review")
    void proposeComputesKey() {
        CatalogDTOs.Proposal p = service.propose(a32(1L, "128GB"));
        assertThat(p.proposedIdentityKey()).isEqualTo("SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|12M");
        assertThat(p.matchStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(p.sourceProductName()).as("snapshot from catalog, not the request").isEqualTo("Galaxy A32 128 Black");
        assertThat(p.mktProductId()).isNull();
        assertThat(productRows).as("nothing is published by proposing").isEmpty();
    }

    @Test
    @DisplayName("[MKT-R6.1] [MKT-R5.4] a second seller with the same key gets a SUGGESTION, never an automatic merge")
    void suggestionNotMerge() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("MATCHED", null, null));
        long canonical = sourceRows.get(1L).getMktProductId();
        org = SELLER_B;
        CatalogDTOs.Proposal b = service.propose(new CatalogDTOs.ProposeRequest(5L, " samsung ", "galaxy  a32", "128 gb",
                "BLACK", null, null, null, "new", "12m", null));
        assertThat(b.suggestedProductId()).isEqualTo(canonical);
        assertThat(b.matchStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(b.mktProductId()).isNull();
        org = SELLER_A;
        service.decide(b.id(), decision("MATCHED", null, null));
        assertThat(sourceRows.get(b.id()).getMktProductId()).as("two sellers, one canonical product").isEqualTo(canonical);
        assertThat(productRows).hasSize(1);
    }

    @Test
    @DisplayName("[MKT-R6.6] 64GB is never suggested for the 128GB product")
    void storageNeverSuggested() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("MATCHED", null, null));
        org = SELLER_B;
        CatalogDTOs.Proposal p = service.propose(a32(6L, "64GB"));
        assertThat(p.proposedIdentityKey()).contains("|64GB|");
        assertThat(p.suggestedProductId()).isNull();
    }

    @Test
    @DisplayName("[MKT-R22.1] another tenant's product id reads as 'not in your catalogue', and nothing is saved")
    void foreignProduct() {
        org = SELLER_B;
        assertThatThrownBy(() -> service.propose(a32(1L, "128GB"))).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("That product is not in your catalogue.");
        verify(sources, never()).save(any());
    }

    @Test
    @DisplayName("[MKT-R20.2] [MKT-R7.6] a prescription product is refused by the catalog's flag, whatever the request says")
    void regulatedRefused() {
        assertThatThrownBy(() -> service.propose(new CatalogDTOs.ProposeRequest(2L, "GSK", "Panadol", null, null,
                null, null, "10", null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Prescription and restricted products cannot be sold on the marketplace yet.");
        verify(sources, never()).save(any());
    }

    @Test
    @DisplayName("[MKT-R20.1] a seller that is not active (not approved, no agreements, module off) cannot propose")
    void activeSellerRequired() {
        doThrow(new ValidationException("MaxTheService is still reviewing your seller account.")).when(sellers).assertActiveSeller();
        assertThatThrownBy(() -> service.propose(a32(1L, "128GB"))).hasMessageContaining("still reviewing");
        verify(catalog, never()).getProductsFresh(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("[MKT-R6.2] a valid GTIN becomes the key; a mistyped one is ignored, never trusted")
    void gtin() {
        CatalogDTOs.Proposal good = service.propose(new CatalogDTOs.ProposeRequest(1L, "Samsung", "Galaxy A32",
                "128GB", "Black", null, null, null, "New", null, "4006381333931"));
        assertThat(good.proposedIdentityKey()).isEqualTo("GTIN:4006381333931");
        assertThat(good.gtin()).isEqualTo("4006381333931");
        org = SELLER_B;
        CatalogDTOs.Proposal bad = service.propose(new CatalogDTOs.ProposeRequest(5L, "Samsung", "Galaxy A32",
                "128GB", "Black", null, null, null, "New", null, "4006381333932"));
        assertThat(bad.proposedIdentityKey()).startsWith("SAMSUNG|");
        assertThat(bad.gtin()).isNull();
    }

    @Test
    @DisplayName("[MKT-R6.5] the operator corrects a bad match: MATCHED → NEEDS_CORRECTION detaches it, with a note")
    void correction() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("MATCHED", null, null));
        assertThatThrownBy(() -> service.decide(1L, decision("NEEDS_CORRECTION", null, " ")))
                .hasMessageContaining("Tell the seller what is wrong");
        CatalogDTOs.Proposal c = service.decide(1L, decision("NEEDS_CORRECTION", null, "colour is Blue on the box"));
        assertThat(c.matchStatus()).isEqualTo("NEEDS_CORRECTION");
        assertThat(c.mktProductId()).isNull();
        assertThat(c.reviewNote()).isEqualTo("colour is Blue on the box");
        // the seller fixes it and re-proposes: back in the queue
        CatalogDTOs.Proposal again = service.propose(new CatalogDTOs.ProposeRequest(1L, "Samsung", "Galaxy A32",
                "128GB", "Blue", null, null, null, "New", "12M", null));
        assertThat(again.matchStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(again.reviewNote()).isNull();
        assertThat(again.id()).as("same row, one history").isEqualTo(1L);
    }

    @Test
    @DisplayName("[MKT-R6.4] a matched product cannot be re-proposed by the seller — only the operator corrects it")
    void matchedIsLocked() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("MATCHED", null, null));
        assertThatThrownBy(() -> service.propose(a32(1L, "64GB"))).hasMessageContaining("already matched");
    }

    @Test
    @DisplayName("[MKT-R5.2] a new canonical product is APPROVED, named from its attributes and carries the regulated status")
    void newCanonical() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("MATCHED", null, null));
        MarketplaceProduct p = productRows.values().iterator().next();
        assertThat(p.getCanonicalName()).isEqualTo("Samsung Galaxy A32 128GB Black");
        assertThat(p.getApprovalStatus()).isEqualTo("APPROVED");
        assertThat(p.getRegulatedStatus()).isEqualTo("NONE");
        assertThat(p.getIdentityKey()).isEqualTo("SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|12M");
    }

    @Test
    @DisplayName("[MKT-R7.6] a regulated product can never be attached to an ordinary canonical row (no laundering)")
    void noRegulatedLaundering() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("MATCHED", null, null));
        long ordinary = sourceRows.get(1L).getMktProductId();
        stock(SELLER_B, 9L, "Samsung A32 (rx flag set by mistake)", false, true);
        org = SELLER_B;
        // the regulated proposal is refused at the door in Phase 1; prove the operator path holds too
        MarketplaceProductSource s = new MarketplaceProductSource();
        s.setId(50L);
        s.setOrganizationId(SELLER_B);
        s.setSourceProductId(9L);
        s.setMatchStatus("PENDING_REVIEW");
        s.setSourceRegulated("RESTRICTED");
        s.setProposedIdentityKey("X");
        sourceRows.put(50L, s);
        assertThatThrownBy(() -> service.decide(50L, decision("MATCHED", ordinary, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("cannot be the same product");
    }

    @Test
    @DisplayName("[MKT-R22.1] only the operator reviews; a tenant is refused before anything is read")
    void operatorOnly() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.decide(1L, decision("MATCHED", null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.queue(null, 0, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.canonicalProducts(null, 0, 10)).isInstanceOf(AccessDeniedException.class);
        verify(sources, never()).findById(anyLong());
    }

    @Test
    @DisplayName("[MKT-R19.1] an illegal review move is refused in words")
    void illegalMove() {
        service.propose(a32(1L, "128GB"));
        service.decide(1L, decision("REJECTED", null, "not a phone"));
        assertThatThrownBy(() -> service.decide(1L, decision("MATCHED", null, null)))
                .hasMessage("A proposal that is rejected cannot be moved to matched.");
    }

    @Test
    @DisplayName("[MKT-R6.4] the waiting queue is worked oldest first; a decided list shows the newest decision first")
    void queueOrder() {
        when(sources.findByMatchStatusOrderByCreatedAtAsc(anyString(), any())).thenReturn(Page.empty());
        when(sources.findByMatchStatusOrderByCreatedAtDesc(anyString(), any())).thenReturn(Page.empty());
        service.queue(null, 0, 10);
        service.queue("matched", 0, 10);
        service.queue("REJECTED", 0, 10);
        verify(sources).findByMatchStatusOrderByCreatedAtAsc(eq("PENDING_REVIEW"), any());
        verify(sources).findByMatchStatusOrderByCreatedAtDesc(eq("MATCHED"), any());
        verify(sources).findByMatchStatusOrderByCreatedAtDesc(eq("REJECTED"), any());
    }
}
