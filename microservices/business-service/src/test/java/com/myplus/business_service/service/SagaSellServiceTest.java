package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 33, U3b — pure Mockito (always runs). Verifies the saga orchestration: happy path confirms;
 * OUT_OF_STOCK rejects before writing; a confirm failure leaves the invoice PENDING (no release, not marked).
 */
@ExtendWith(MockitoExtension.class)
class SagaSellServiceTest {

    @Mock private ItemCatalogMapRepo itemCatalogMapRepo;
    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private SagaSaleWriter saleWriter;
    @Mock private RequestUtil requestUtil;
    @Mock private TaxService taxService;
    @InjectMocks private SagaSellService service;

    private CustomerHistoryDTO dtoWithOneLine() {
        SellDTO s = new SellDTO();
        s.setItemId(5L);
        s.setQuantity(2f);
        s.setTotalAmount(new BigDecimal("20.00"));
        s.setNetAmount(new BigDecimal("20.00"));
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setSales(List.of(s));
        return dto;
    }

    private CustomerHistory invoice(Long id, String no) {
        CustomerHistory ch = new CustomerHistory();
        ch.setCustomer_history_id(id);
        ch.setInvoiceNo(no);
        return ch;
    }

    @BeforeEach
    void user() {
        when(requestUtil.getCurrentUser())
                .thenReturn(new AuthenticatedUser(1L, "cashier@test.com", List.of(), 1L));
        // G3: tax disabled in these saga-orchestration tests — no tax applied, lines carry zero tax.
        when(taxService.settingsFor(anyLong())).thenReturn(TaxSetting.builder().enabled(false).build());
        when(taxService.taxForLine(any(), any(), any()))
                .thenReturn(new TaxResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void happy_path_reserves_writes_confirms_and_marks_confirmed() {
        when(itemCatalogMapRepo.findProductIdByItemId(5L, 1L)).thenReturn(Optional.of(50L));
        when(catalogClient.getProduct(50L))
                .thenReturn(new ProductRef(50L, "SKU", "Name", "ea", new BigDecimal("10.00"), null));
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
        when(saleWriter.writePending(any(), eq("R1"), anyString(), any(), anyList()))
                .thenReturn(invoice(1000L, "INV-000001"));

        String invoiceNo = service.addSell(dtoWithOneLine());

        assertThat(invoiceNo).isEqualTo("INV-000001");
        verify(inventoryClient).confirm("R1");
        verify(saleWriter).markStatus(1000L, "CONFIRMED");
    }

    @Test
    void out_of_stock_rejects_before_writing_anything() {
        when(itemCatalogMapRepo.findProductIdByItemId(5L, 1L)).thenReturn(Optional.of(50L));
        when(catalogClient.getProduct(50L))
                .thenReturn(new ProductRef(50L, "SKU", "Name", "ea", new BigDecimal("10.00"), null));
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse(null, ReservationStatus.OUT_OF_STOCK, List.of(), "no stock"));

        assertThatThrownBy(() -> service.addSell(dtoWithOneLine()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Insufficient stock");

        verify(saleWriter, never()).writePending(any(), any(), any(), any(), any());
    }

    @Test
    void confirm_failure_leaves_invoice_pending_for_the_relay() {
        when(itemCatalogMapRepo.findProductIdByItemId(5L, 1L)).thenReturn(Optional.of(50L));
        when(catalogClient.getProduct(50L))
                .thenReturn(new ProductRef(50L, "SKU", "Name", "ea", new BigDecimal("10.00"), null));
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
        when(saleWriter.writePending(any(), eq("R1"), anyString(), any(), anyList()))
                .thenReturn(invoice(1000L, "INV-000002"));
        when(inventoryClient.confirm("R1")).thenThrow(new RuntimeException("inventory down"));

        String invoiceNo = service.addSell(dtoWithOneLine());

        assertThat(invoiceNo).isEqualTo("INV-000002");          // sale still recorded
        verify(saleWriter, never()).markStatus(1000L, "CONFIRMED");
        verify(inventoryClient, never()).release(anyString());  // PENDING left for the relay, hold retained
    }
}
