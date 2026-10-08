package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.SellerPerformance;
import com.myplus.marketplace.multiseller.dto.PerformanceDTOs;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;

/** MKT-2e — the scorecard service: who may read it, the window, the COD flag, and the ranking's figures. */
@ExtendWith(MockitoExtension.class)
class SellerPerformanceServiceTest {

    @Mock MarketplaceSellerOrderRepository sellerOrders;
    @Mock MarketplaceReturnRepository returns;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceSettlementEntryRepository entries;
    @Mock CodStandingService cod;
    @Mock SellerAccess access;
    SellerPerformanceService service;

    static final LocalDateTime T = LocalDateTime.now().minusDays(3);

    @BeforeEach
    void wire() {
        service = new SellerPerformanceService(sellerOrders, returns, accounts, entries, cod, access);
        lenient().when(accounts.findByOrganizationId(any())).thenAnswer(i -> {
            MarketplaceSellerAccount a = new MarketplaceSellerAccount();
            a.setDisplayName(((Long) i.getArgument(0)) == 7L ? "Shahzad Mobile Shop" : "Mobile Distributor");
            return Optional.of(a);
        });
        lenient().when(returns.sellerFaultReturns(any())).thenReturn(List.<Object[]>of(new Object[] { 7L, 2L }));
        lenient().when(entries.balances()).thenReturn(List.of());
    }

    static Object[] fact(long org, String status, String party, String shortageStatus) {
        return new Object[] { org, status, T, "ACCEPTED".equals(status) ? T.plusMinutes(12) : null,
                "ACCEPTED".equals(status) ? T.plusHours(5) : null, 24, party, shortageStatus };
    }

    /** Seller 7: 6 accepted, 3 missed (67%). Seller 8: 5 accepted. */
    static List<Object[]> facts() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(fact(7, "ACCEPTED", null, null));
        for (int i = 0; i < 3; i++) rows.add(fact(7, "REJECTED", "MERCHANT", "RECORDED"));
        for (int i = 0; i < 5; i++) rows.add(fact(8, "ACCEPTED", null, null));
        return rows;
    }

    @Test
    @DisplayName("[MKT-R20.3] the operator sees every seller with an order in the window, flagged first, with returns it caused")
    void operatorScorecard() {
        when(sellerOrders.performanceFacts(any())).thenReturn(facts());
        PerformanceDTOs.PerformanceView v = service.operator(30);
        assertThat(v.days()).isEqualTo(30);
        assertThat(v.minOrders()).isEqualTo(SellerPerformance.MIN_ORDERS);
        assertThat(v.sellers()).extracting(PerformanceDTOs.SellerScore::organizationId).containsExactly(7L, 8L);
        PerformanceDTOs.SellerScore a = v.sellers().get(0);
        assertThat(a.sellerName()).isEqualTo("Shahzad Mobile Shop");
        assertThat(a.acceptanceRate()).isEqualTo(6d / 9);
        assertThat(a.avgMinutesToAccept()).isEqualTo(12L);
        assertThat(a.onTimeRate()).isEqualTo(1d);
        assertThat(a.sellerFaultReturns()).isEqualTo(2L);
        assertThat(a.flags()).containsExactly(SellerPerformance.FLAG_ACCEPTANCE);
        assertThat(v.sellers().get(1).flags()).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R20.3] late for cash orders (MKT-2d) is flagged; only sellers that owe are walked")
    void codFlag() {
        when(sellerOrders.performanceFacts(any())).thenReturn(facts());
        when(entries.balances()).thenReturn(List.of(new Object[] { 8L, new BigDecimal("-4160.00") }, new Object[] { 7L, new BigDecimal("100.00") }));
        when(cod.standing(eq(8L), any(), any())).thenReturn(new SettlementDTOs.CodStanding(new BigDecimal("4160.00"),
                LocalDate.now().minusDays(12), LocalDate.now().minusDays(5), true, false));
        PerformanceDTOs.PerformanceView v = service.operator(null);
        PerformanceDTOs.SellerScore b = v.sellers().stream().filter(r -> r.organizationId() == 8L).findFirst().orElseThrow();
        assertThat(b.codOverdue()).isTrue();
        assertThat(b.flags()).containsExactly(SellerPerformance.FLAG_COD);
        verify(cod, never()).standing(eq(7L), any(), any());
    }

    @Test
    @DisplayName("[MKT-R20.3] the window is 7, 30 or 90 days (30 when not given); anything else is refused")
    void windows() {
        when(sellerOrders.performanceFacts(any())).thenReturn(List.of());
        assertThat(service.operator(null).days()).isEqualTo(30);
        assertThat(service.operator(7).days()).isEqualTo(7);
        assertThat(service.operator(90).from()).isEqualTo(LocalDate.now().minusDays(90));
        assertThatThrownBy(() -> service.operator(365)).isInstanceOf(ValidationException.class).hasMessage("Choose 7, 30 or 90 days.");
    }

    @Test
    @DisplayName("[MKT-R20.3] [MKT-R22.1] the operator's scorecard is the operator's; a seller reads only its own")
    void access() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.operator(30)).isInstanceOf(AccessDeniedException.class);
        verify(sellerOrders, never()).performanceFacts(any());

        when(access.org()).thenReturn(8L);
        when(sellerOrders.performanceFactsOf(any(), eq(8L))).thenReturn(facts().subList(9, 14));
        when(cod.standing(8L)).thenReturn(new SettlementDTOs.CodStanding(BigDecimal.ZERO, null, null, false, false));
        PerformanceDTOs.PerformanceView mine = service.mine(30);
        assertThat(mine.sellers()).extracting(PerformanceDTOs.SellerScore::organizationId).containsExactly(8L);
        assertThat(mine.sellers().get(0).sellerFaultReturns()).as("seller 7's returns are not seller 8's").isZero();
        verify(sellerOrders, never()).performanceFacts(any());
    }

    @Test
    @DisplayName("[MKT-R20.3] ranking: 1.0 until known; the 30-day figures once refreshed; too few orders stays 1.0")
    void rankingRates() {
        assertThat(service.acceptanceRate(7L)).isEqualTo(1d);
        List<Object[]> rows = facts();
        rows.add(fact(9, "REJECTED", "MERCHANT", "RECORDED"));            // seller 9: 1 order, too few
        when(sellerOrders.performanceFacts(any())).thenReturn(rows);
        service.refreshRanking();
        assertThat(service.acceptanceRate(7L)).isEqualTo(6d / 9);
        assertThat(service.acceptanceRate(8L)).isEqualTo(1d);
        assertThat(service.acceptanceRate(9L)).isEqualTo(1d);
        assertThat(service.acceptanceRate(null)).isEqualTo(1d);

        when(sellerOrders.performanceFacts(any())).thenThrow(new IllegalStateException("db down"));
        service.refreshRanking();
        assertThat(service.acceptanceRate(7L)).as("a failed refresh keeps the last figures").isEqualTo(6d / 9);
    }

    @Test
    @DisplayName("[MKT-R20.3] opening the 30-day scorecard refreshes the ranking; another window does not")
    void scorecardRefreshesRanking() {
        when(sellerOrders.performanceFacts(any())).thenReturn(facts());
        service.operator(7);
        assertThat(service.acceptanceRate(7L)).isEqualTo(1d);
        service.operator(30);
        assertThat(service.acceptanceRate(7L)).isEqualTo(6d / 9);
    }
}
