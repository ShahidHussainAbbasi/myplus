package com.myplus.business_service.service;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The local DB writes of the sell saga (slice 33, U3b), in their own committed transactions
 * ({@code REQUIRES_NEW}) so the PENDING sale is durable BEFORE the orchestrator calls {@code confirm} on
 * inventory — and is independent of the caller's (legacy) transaction. Separate bean so the proxy applies
 * (no self-invocation).
 */
@Service
@RequiredArgsConstructor
public class SagaSaleWriter {

    private final ICustomerService customerService;
    private final ICustomerHistoryService customerHistoryService;
    private final ISellService sellService;

    /** Write Customer + invoice (PENDING, carrying the reservation) + Sell lines (productId, catalog rate). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CustomerHistory writePending(CustomerHistoryDTO dto, String reservationId, String idempotencyKey,
                                        AuthenticatedUser user, List<SagaLine> lines) {
        dto.setUserId(user.getUserId());
        Customer customer;
        try {
            customer = customerService.saveUpdateCustomer(dto);   // declares checked Exception
        } catch (Exception e) {
            throw new RuntimeException("Failed to save customer for sale", e);
        }
        customerService.save(customer);

        CustomerHistory ch = customerHistoryService.saveUpdateCustomerHistory(dto);
        ch.setCustomer(customer);
        ch.setReservationId(reservationId);
        ch.setIdempotencyKey(idempotencyKey);
        ch.setSagaStatus("PENDING");
        customerHistoryService.save(ch);

        customerService.recomputeDue(customer);

        LocalDateTime now = LocalDateTime.now();
        for (SagaLine l : lines) {
            Sell sell = new Sell();
            sell.setUserId(user.getUserId());
            sell.setOrganizationId(user.getOrganizationId());
            sell.setProductId(l.productId());        // saga sell: catalog product, no local Stock FK
            sell.setQuantity(l.quantity());
            sell.setSellRate(l.sellRate());          // catalog list price (D1)
            sell.setDiscount(l.discount());
            sell.setTotalAmount(l.totalAmount());
            sell.setNetAmount(l.netAmount());
            sell.setSrp(l.srp());
            sell.setCustomerHistory(ch);
            sell.setDated(now);
            sell.setUpdated(now);
            sellService.save(sell);
        }
        return ch;
    }

    /** Flip the invoice's saga status (PENDING → CONFIRMED/FAILED) in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStatus(Long customerHistoryId, String status) {
        customerHistoryService.findById(customerHistoryId).ifPresent(ch -> {
            ch.setSagaStatus(status);
            customerHistoryService.save(ch);
        });
    }
}
