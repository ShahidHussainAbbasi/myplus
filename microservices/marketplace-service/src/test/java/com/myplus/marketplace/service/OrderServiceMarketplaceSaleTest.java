package com.myplus.marketplace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.repository.OrderRepository;

/**
 * MKT-1e — {@link OrderService#placeMarketplace}: the seller's acceptance becomes a sale in the SELLER's books through
 * the O1 sale path. A plain unit test on purpose: {@code OrderServiceTest} needs Docker and is skipped in CI-less runs.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceMarketplaceSaleTest {

    @Mock OrderRepository repo;
    @Mock TradeClient tradeClient;
    @Mock NotificationService notificationService;
    @InjectMocks OrderService service;

    static OrderService.MarketplaceSale sale(List<String> serials) {
        return new OrderService.MarketplaceSale(7L, "MKT-SO-42", "MKT-000123", "Ali", "03001234567", "1 Clifton, Karachi",
                List.of(new OrderService.MarketplaceSaleLine(555L, "Samsung Galaxy A32 128GB Black", 1,
                        new BigDecimal("52000.00"), serials)));
    }

    static SaleRecordResult recorded() {
        SaleRecordResult r = new SaleRecordResult();
        r.setInvoiceNo("INV-000099");
        r.setGrandTotal(new BigDecimal("52000.00"));
        return r;
    }

    @Test
    @DisplayName("[MKT-R10.1] [MKT-R1.3] the sale: channel MARKETPLACE, the marketplace price, the IMEIs, COD (no tender), key MKT-SO-{id}")
    void recordsTheSale() {
        when(repo.findByOrgAndIdempotencyKey(7L, "MKT-SO-42")).thenReturn(Optional.empty());
        when(tradeClient.recordSale(any())).thenReturn(recorded());
        when(repo.maxOrderSeqForOrg(7L)).thenReturn(41L);
        when(repo.saveAndFlush(any())).thenAnswer(i -> { Order o = i.getArgument(0); o.setId(9001L); return o; });

        OrderDTO d = service.placeMarketplace(sale(List.of("356938035643809")));

        ArgumentCaptor<SaleRecordRequest> req = ArgumentCaptor.forClass(SaleRecordRequest.class);
        verify(tradeClient).recordSale(req.capture());
        SaleRecordRequest r = req.getValue();
        assertThat(r.getOrganizationId()).isEqualTo(7L);
        assertThat(r.getIdempotencyKey()).isEqualTo("MKT-SO-42");
        assertThat(r.getChannel()).isEqualTo("MARKETPLACE");
        assertThat(r.getTenders()).as("COD: a receivable, no tender").isEmpty();
        assertThat(r.getLines().get(0).getUnitPrice()).isEqualByComparingTo("52000");
        assertThat(r.getLines().get(0).getSerials()).isEqualTo("356938035643809");
        assertThat(r.getCustomer().getContact()).isEqualTo("03001234567");

        ArgumentCaptor<Order> o = ArgumentCaptor.forClass(Order.class);
        verify(repo).saveAndFlush(o.capture());
        assertThat(o.getValue().getSource()).isEqualTo("MARKETPLACE");
        assertThat(o.getValue().getInvoiceNo()).isEqualTo("INV-000099");
        assertThat(o.getValue().getBooksStatus()).isEqualTo("POSTED");
        assertThat(o.getValue().getItems()).hasSize(1);
        assertThat(o.getValue().getItems().get(0).getQuantityBackordered()).as("never split into a backorder").isZero();
        assertThat(d.getInvoiceNo()).isEqualTo("INV-000099");
    }

    @Test
    @DisplayName("[MKT-R22.3] a retried accept replays the existing store order and never sells twice")
    void replay() {
        Order existing = Order.builder().id(9001L).organizationId(7L).orderNo("SO-000042").invoiceNo("INV-000099")
                .idempotencyKey("MKT-SO-42").build();
        when(repo.findByOrgAndIdempotencyKey(7L, "MKT-SO-42")).thenReturn(Optional.of(existing));
        assertThat(service.placeMarketplace(sale(null)).getOrderNo()).isEqualTo("SO-000042");
        verify(tradeClient, never()).recordSale(any());
    }

    @Test
    @DisplayName("[MKT-R10.1] the order row cannot be written: the sale just made is reversed, not left in the books")
    void compensates() {
        when(repo.findByOrgAndIdempotencyKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(tradeClient.recordSale(any())).thenReturn(recorded());
        when(repo.maxOrderSeqForOrg(7L)).thenReturn(41L);
        when(repo.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.placeMarketplace(sale(null))).hasMessage("db down");
        verify(tradeClient).reverseSale(eq("INV-000099"), anyString());
    }
}
