package com.myplus.business_service.service;

import com.myplus.business_service.config.TradeSagaProperties;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recovery relay for the sell↔stock saga (slice 33, U3c / UD1). Re-drives {@code confirm} for sales left
 * PENDING when trade crashed between writing the sale and confirming the reservation. {@code confirm} is
 * idempotent, so retrying is safe. Runs only while the saga is enabled. Background job → no inbound request,
 * so it impersonates each invoice's tenant via {@link GatewayIdentityForwarding#runAs} for the scoped call.
 */
@Component
@RequiredArgsConstructor
public class SagaRecoveryRelay {

    private static final Logger LOG = LoggerFactory.getLogger(SagaRecoveryRelay.class);

    private final CustomerHistoryRepo customerHistoryRepo;
    private final InventoryClient inventoryClient;
    private final SagaSaleWriter saleWriter;
    private final TradeSagaProperties tradeSagaProperties;

    @Scheduled(fixedDelayString = "${trade.saga.relay-delay-ms:60000}")
    public void reconfirmPending() {
        if (!tradeSagaProperties.isEnabled()) return;

        List<CustomerHistory> pending = customerHistoryRepo.findPendingSagaSales();
        for (CustomerHistory ch : pending) {
            try {
                GatewayIdentityForwarding.runAs(ch.getUserId(), ch.getOrganizationId(),
                        () -> inventoryClient.confirm(ch.getReservationId()));
                saleWriter.markStatus(ch.getCustomer_history_id(), "CONFIRMED");
                LOG.info("Saga relay confirmed pending invoice {} (reservation {})",
                        ch.getInvoiceNo(), ch.getReservationId());
            } catch (RuntimeException e) {
                // Reservation maybe transiently unreachable — leave PENDING and retry next tick.
                LOG.warn("Saga relay could not confirm invoice {} (reservation {}); will retry",
                        ch.getInvoiceNo(), ch.getReservationId(), e);
            }
        }
    }
}
