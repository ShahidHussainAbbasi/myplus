package com.myplus.marketplace.service;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.common.security.GatewayIdentityForwarding;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recovery relay for the storefront sell↔stock saga (slice 52) — the marketplace counterpart of POS's
 * SagaRecoveryRelay (slice 33, U3c). Re-drives {@code confirm} for storefront orders left with a PENDING
 * reservation when placement crashed/timed out between reserve and confirm. {@code confirm} is idempotent, so
 * retrying is safe. Background job → no inbound request, so it impersonates each order's tenant via
 * {@link GatewayIdentityForwarding#runAs} for the scoped call.
 */
@Component
@RequiredArgsConstructor
public class OrderSagaRecoveryRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OrderSagaRecoveryRelay.class);
    /** Same synthetic actor placePublic reserved under (org carries the tenant; stock is org-scoped). */
    private static final Long STOREFRONT_USER = 0L;

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;

    @Scheduled(fixedDelayString = "${marketplace.saga.relay-delay-ms:60000}")
    public void reconfirmPending() {
        List<Order> pending = orderRepository.findPendingReservations();
        for (Order o : pending) {
            try {
                GatewayIdentityForwarding.runAs(STOREFRONT_USER, o.getOrganizationId(),
                        () -> inventoryClient.confirm(o.getReservationId()));
                o.setReservationStatus("CONFIRMED");
                orderRepository.save(o);
                LOG.info("Marketplace saga relay confirmed order {} (reservation {})", o.getId(), o.getReservationId());
            } catch (RuntimeException e) {
                // Reservation maybe transiently unreachable — leave PENDING and retry next tick.
                LOG.warn("Marketplace saga relay could not confirm order {} (reservation {}); will retry",
                        o.getId(), o.getReservationId(), e);
            }
        }
    }
}
