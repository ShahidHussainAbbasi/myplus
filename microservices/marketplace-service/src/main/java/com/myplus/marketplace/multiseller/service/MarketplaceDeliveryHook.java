package com.myplus.marketplace.multiseller.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.myplus.marketplace.entity.FulfilmentStatus;
import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1f — tells the marketplace when the seller's store order was delivered.
 *
 * <p>After Accept, the store order runs fulfilment (O2/O5b); the marketplace order deliberately keeps no copy of those
 * states. What it needs is ONE fact — when it was delivered — because the return window runs from there. The two
 * writers that can deliver a store order call this: {@code OrderService.updateStatus} and {@code DeliveryService}.
 * Stamped once: a second delivery event (a later parcel, a re-key) never moves the window.
 *
 * <p>Runs inside the caller's transaction, so a rolled-back delivery stamps nothing.
 */
@Component
@RequiredArgsConstructor
public class MarketplaceDeliveryHook {

    private final MarketplaceSellerOrderRepository sellerOrders;

    public void afterSave(Order storeOrder) {
        if (storeOrder == null || storeOrder.getId() == null || !"MARKETPLACE".equals(storeOrder.getSource())
                || storeOrder.getFulfilmentStatus() != FulfilmentStatus.DELIVERED) return;
        sellerOrders.findFirstByStoreOrderId(storeOrder.getId()).ifPresent(so -> {
            if (so.getDeliveredAt() != null) return;
            so.setDeliveredAt(LocalDateTime.now());
            sellerOrders.save(so);
        });
    }
}
