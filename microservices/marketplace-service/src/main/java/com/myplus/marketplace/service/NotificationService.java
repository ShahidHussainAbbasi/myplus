package com.myplus.marketplace.service;

import com.myplus.marketplace.entity.Order;
import com.myplus.marketplace.entity.OrderEvent;
import com.myplus.marketplace.repository.OrderEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Order notifications (slice 57) — records a fulfilment-timeline event per status transition and (best-effort)
 * notifies the customer. Real email/SMS delivery is a config follow-on (reuse education-service's EmailService);
 * for now the channel is decided from the contact and the send is logged. Never breaks the order flow.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    private final OrderEventRepository eventRepo;

    /** Append a timeline event for {@code order} reaching {@code status}, and best-effort notify the customer. */
    public OrderEvent notify(Order order, String status, String note) {
        String contact = order.getCustomerContact();
        boolean email = contact != null && contact.contains("@");
        String channel = email ? "EMAIL" : "LOG";
        try {
            if (email) {
                // TODO real delivery: reuse education-service EmailService (SMTP) — config follow-on.
                LOG.info("Order #{} -> {}: would email {} ({})", order.getId(), status, contact, note);
            } else {
                LOG.info("Order #{} -> {}: {} ({})", order.getId(), status, contact, note);
            }
        } catch (RuntimeException ignore) {
            LOG.warn("Notification send failed for order {} ({}) — event still recorded", order.getId(), status);
        }
        return eventRepo.save(OrderEvent.builder()
                .orderId(order.getId()).status(status).channel(channel).recipient(contact).note(note)
                .build());
    }
}
