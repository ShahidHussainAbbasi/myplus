package com.myplus.business_service.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.myplus.business_service.config.TradeSagaProperties;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.commerce.contracts.client.InventoryClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 33, U3c — pure Mockito (always runs). The relay re-confirms PENDING saga sales when enabled, and
 * is a no-op when the saga is off.
 */
@ExtendWith(MockitoExtension.class)
class SagaRecoveryRelayTest {

    @Mock private CustomerHistoryRepo customerHistoryRepo;
    @Mock private InventoryClient inventoryClient;
    @Mock private SagaSaleWriter saleWriter;
    @Mock private TradeSagaProperties tradeSagaProperties;
    @InjectMocks private SagaRecoveryRelay relay;

    private CustomerHistory pending(Long id, String reservationId, String invoiceNo) {
        CustomerHistory ch = new CustomerHistory();
        ch.setCustomer_history_id(id);
        ch.setUserId(9L);
        ch.setOrganizationId(1L);
        ch.setReservationId(reservationId);
        ch.setInvoiceNo(invoiceNo);
        ch.setSagaStatus("PENDING");
        return ch;
    }

    @Test
    void reconfirms_pending_sales_when_enabled() {
        when(tradeSagaProperties.isEnabled()).thenReturn(true);
        when(customerHistoryRepo.findPendingSagaSales())
                .thenReturn(List.of(pending(1000L, "R1", "INV-000001")));

        relay.reconfirmPending();

        verify(inventoryClient).confirm("R1");
        verify(saleWriter).markStatus(1000L, "CONFIRMED");
    }

    @Test
    void does_nothing_when_saga_disabled() {
        when(tradeSagaProperties.isEnabled()).thenReturn(false);

        relay.reconfirmPending();

        verify(customerHistoryRepo, never()).findPendingSagaSales();
        verify(inventoryClient, never()).confirm(anyString());
    }
}
