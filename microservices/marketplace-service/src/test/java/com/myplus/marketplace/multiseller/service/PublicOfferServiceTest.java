package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myplus.marketplace.multiseller.dto.OfferDTOs;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplacePlatformSetting;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePlatformSettingRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;

/** MKT-1c/1d — the public catalogue: the source's own two A32 offers, through the guardrails and the sort. */
@ExtendWith(MockitoExtension.class)
class PublicOfferServiceTest {

    @Mock MarketplaceOfferProjectionRepository projections;
    @Mock MarketplaceProductRepository products;
    @Mock MarketplacePlatformSettingRepository settingRows;
    @Mock SellerAccess access;
    @Mock SellerPerformanceService performance;
    PublicOfferService service;
    MarketplacePlatformSetting defaultSort;

    final List<MarketplaceOfferProjection> live = new ArrayList<>();
    MarketplaceProduct product;

    @BeforeEach
    void wire() {
        // the REAL settings service over a mocked table: the fallback and RECOMMENDED rules are under test too
        service = new PublicOfferService(projections, products, new MarketplaceSettingsService(settingRows, access, org.mockito.Mockito.mock(MarketplaceAuditService.class)), performance);
        lenient().when(performance.acceptanceRate(anyLong())).thenReturn(1d);
        lenient().when(settingRows.findById(MarketplacePlatformSetting.DEFAULT_SORT))
                .thenAnswer(i -> Optional.ofNullable(defaultSort));
        product = new MarketplaceProduct();
        product.setId(100L);
        product.setApprovalStatus("APPROVED");
        product.setRegulatedStatus("NONE");
        lenient().when(products.findById(anyLong())).thenAnswer(i -> Optional.of(product));
        lenient().when(projections.findByMktProductIdAndStatusOrderByPriceAsc(100L, "LIVE")).thenReturn(live);
        live.add(row(1, "Shahzad Mobile Shop", "52000", 4, 12, "Karachi", LocalDateTime.now().minusMinutes(1)));
        live.add(row(2, "Mobile Distributor", "51500", 24, 6, "Karachi,Lahore", LocalDateTime.now().minusMinutes(1)));
    }

    static MarketplaceOfferProjection row(long id, String seller, String price, int hours, int warranty, String areas,
            LocalDateTime synced) {
        MarketplaceOfferProjection p = new MarketplaceOfferProjection();
        p.setOfferId(id);
        p.setMktProductId(100L);
        p.setSellerOrganizationId(id + 6);
        p.setSellerDisplayName(seller);
        p.setStockSourceType("MERCHANT");
        p.setRegulatedStatus("NONE");
        p.setPrice(new BigDecimal(price));
        p.setAvailableQty(new BigDecimal("5"));
        p.setPromiseHours(hours);
        p.setWarrantyMonths(warranty);
        p.setReturnDays(7);
        p.setDeliveryAreas(areas);
        p.setStatus("LIVE");
        p.setLastSyncAt(synced);
        return p;
    }

    static List<Long> ids(List<OfferDTOs.PublicOffer> o) {
        return o.stream().map(OfferDTOs.PublicOffer::offerId).toList();
    }

    @Test
    @DisplayName("[MKT-R7.1] [MKT-R5.4] the customer's sort decides the order; the cheapest is not forced")
    void sorts() {
        assertThat(ids(service.offers(100L, "Karachi", "LOWEST_PRICE", null))).containsExactly(2L, 1L);
        assertThat(ids(service.offers(100L, "Karachi", "FASTEST", null))).containsExactly(1L, 2L);
        assertThat(ids(service.offers(100L, "Karachi", "<script>", null))).as("unknown sort → default chain").containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("[MKT-R20.3] MKT-2e: with everything else equal, the seller that accepts more of its orders ranks first")
    void acceptanceBreaksTies() {
        live.clear();
        live.add(row(1, "Shahzad Mobile Shop", "52000", 24, 12, "Karachi", LocalDateTime.now().minusMinutes(1)));
        live.add(row(2, "Mobile Distributor", "52000", 24, 12, "Karachi", LocalDateTime.now().minusMinutes(1)));
        assertThat(ids(service.offers(100L, "Karachi", null, null))).as("a full tie: the offer id decides").containsExactly(1L, 2L);
        when(performance.acceptanceRate(7L)).thenReturn(0.6);       // seller of offer 1 accepted 60%
        assertThat(ids(service.offers(100L, "Karachi", null, null))).containsExactly(2L, 1L);
        assertThat(ids(service.offers(100L, "Karachi", "LOWEST_PRICE", null))).as("a tie under the customer's sort too")
                .containsExactly(2L, 1L);
        live.get(0).setPrice(new BigDecimal("51999"));
        assertThat(ids(service.offers(100L, "Karachi", null, null))).as("only a tie-break: one rupee cheaper still wins")
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R20.1] a city the seller does not serve hides only that seller")
    void city() {
        assertThat(ids(service.offers(100L, "Lahore", null, null))).containsExactly(2L);
        assertThat(ids(service.offers(100L, "Quetta", null, null))).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R18.5] stock not confirmed for 30 minutes, or never, is not shown")
    void stale() {
        live.get(0).setLastSyncAt(LocalDateTime.now().minusMinutes(31));
        live.get(1).setLastSyncAt(null);
        assertThat(service.offers(100L, "Karachi", null, null)).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R7.4] [MKT-R7.6] the operator's price ceiling applies at read time too; a product not approved shows nothing")
    void limitsAndApproval() {
        product.setPriceCeiling(new BigDecimal("51800"));
        assertThat(ids(service.offers(100L, "Karachi", null, null))).containsExactly(2L);
        product.setApprovalStatus("SUSPENDED");
        assertThat(service.offers(100L, "Karachi", null, null)).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R10.3] asking for more than a seller has hides that seller")
    void quantity() {
        live.get(0).setAvailableQty(new BigDecimal("2"));
        assertThat(ids(service.offers(100L, "Karachi", null, new BigDecimal("3")))).containsExactly(2L);
    }

    @Test
    @DisplayName("[MKT-R14.1] [MKT-R9.2] the public view carries warranty and returns, and nothing about cost")
    void view() {
        OfferDTOs.PublicOffer o = service.offers(100L, "Karachi", "FASTEST", null).get(0);
        assertThat(o.sellerName()).isEqualTo("Shahzad Mobile Shop");
        assertThat(o.warrantyMonths()).isEqualTo(12);
        assertThat(o.returnDays()).isEqualTo(7);
        assertThat(o.deliveryAreas()).containsExactly("Karachi");
        assertThat(o.checkedSecondsAgo()).as("server-side age of the stock check (synced 1 min ago)").isBetween(55L, 120L);
        assertThat(java.util.Arrays.stream(OfferDTOs.PublicOffer.class.getRecordComponents()).map(c -> c.getName().toLowerCase()))
                .noneMatch(n -> n.contains("cost") || n.contains("margin") || n.contains("purchase"));
    }

    // ── MKT-1d ──────────────────────────────────────────────────────────────────────────────────────────

    void searchFinds(MarketplaceProduct... ps) {
        when(products.publicSearch(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(
                List.of(ps), org.springframework.data.domain.PageRequest.of(0, 24), ps.length));
        lenient().when(projections.findByMktProductIdInAndStatus(any(), eq("LIVE"))).thenReturn(live);
    }

    @Test
    @DisplayName("[MKT-R5.4] the card says what the table shows: 'Available from 2 sellers · From Rs. 51,500'")
    void cardMatchesTable() {
        searchFinds(product);
        OfferDTOs.ProductCard c = service.search("a32", "Karachi", 0, 24).getContent().get(0);
        assertThat(c.offerCount()).isEqualTo(2).isEqualTo(service.offers(100L, "Karachi", null, null).size());
        assertThat(c.fromPrice()).isEqualByComparingTo("51500");
        assertThat(c.fastestPromiseHours()).isEqualTo(4);
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R18.5] stale stock and other cities leave the card and the table together")
    void cardFollowsGuardrails() {
        searchFinds(product);
        live.get(1).setLastSyncAt(LocalDateTime.now().minusMinutes(45));      // B unconfirmed
        OfferDTOs.ProductCard c = service.search(null, "Karachi", 0, 24).getContent().get(0);
        assertThat(c.offerCount()).isEqualTo(1).isEqualTo(service.offers(100L, "Karachi", null, null).size());
        assertThat(c.fromPrice()).as("'from' recomputes without B").isEqualByComparingTo("52000");
        assertThat(service.search(null, "Quetta", 0, 24).getContent()).as("nothing for Quetta → no card").isEmpty();
    }

    @Test
    @DisplayName("[MKT-R7.6] search text is data: LIKE wildcards are escaped, long text is cut, blank browses")
    void likePattern() {
        assertThat(PublicOfferService.likePattern("Galaxy 50%")).isEqualTo("%galaxy 50!%%");
        assertThat(PublicOfferService.likePattern("a_b!")).isEqualTo("%a!_b!!%");
        assertThat(PublicOfferService.likePattern("  ")).isNull();
        assertThat(PublicOfferService.likePattern("x".repeat(200))).hasSize(PublicOfferService.MAX_Q + 2);
    }

    @Test
    @DisplayName("[MKT-R7.6] at most 24 cards a page, whatever the client asks")
    void pageCap() {
        searchFinds(product);
        service.search(null, null, -3, 5000);
        org.mockito.Mockito.verify(products).publicSearch(org.mockito.ArgumentMatchers.isNull(),
                eq(org.springframework.data.domain.PageRequest.of(0, PublicOfferService.MAX_PAGE)));
    }

    @Test
    @DisplayName("[MKT-R7.4] [MKT-R18.4] no sort → the operator's default; an explicit 'Recommended' stays the customer's choice")
    void operatorDefault() {
        defaultSort = new MarketplacePlatformSetting();
        defaultSort.setSettingValue("LOWEST_PRICE");
        assertThat(ids(service.offers(100L, "Karachi", null, null))).as("operator default").containsExactly(2L, 1L);
        assertThat(ids(service.offers(100L, "Karachi", "nonsense", null))).as("unknown → operator default").containsExactly(2L, 1L);
        assertThat(ids(service.offers(100L, "Karachi", "RECOMMENDED", null))).as("customer chose the chain").containsExactly(1L, 2L);
        defaultSort.setSettingValue("NOT_A_SORT");   // a stale stored value never breaks a browse
        assertThat(ids(service.offers(100L, "Karachi", null, null))).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("[MKT-R7.6] [MKT-R20.2] a regulated or unapproved product reads like one that does not exist")
    void productPage() {
        OfferDTOs.PublicProduct p = service.product(100L);
        assertThat(p.defaultSort()).isEqualTo("RECOMMENDED");
        assertThat(p.sorts()).containsExactly("RECOMMENDED", "LOWEST_PRICE", "FASTEST", "WARRANTY", "RETURN_POLICY");
        product.setRegulatedStatus("PRESCRIPTION");
        assertThatThrownBy(() -> service.product(100L)).isInstanceOf(ResourceNotFoundException.class).hasMessage("No such product.");
        assertThat(service.offers(100L, "Karachi", null, null)).isEmpty();
        product.setRegulatedStatus("NONE");
        product.setApprovalStatus("SUSPENDED");
        assertThatThrownBy(() -> service.product(100L)).hasMessage("No such product.");
    }
}
