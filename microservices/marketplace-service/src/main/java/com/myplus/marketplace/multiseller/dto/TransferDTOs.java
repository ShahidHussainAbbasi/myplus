package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** MKT-3c — stock a seller sends to the MaxTheService warehouse, which MaxTheService buys. */
public final class TransferDTOs {

    private TransferDTOs() { }

    /** A product the seller sells that MaxTheService also carries: the only ones it can send. */
    public record Product(Long mktProductId, String name) { }

    public record OfferRequest(Long mktProductId, Integer quantity, BigDecimal unitPrice, String note) { }

    /** Cancel (the seller), Reject with a reason or Receive with the serials (the warehouse). */
    public record DecisionRequest(String transferNo, Integer version, String reason, List<String> serials) { }

    public record Transfer(String transferNo, String productName, int quantity, BigDecimal unitPrice, BigDecimal total,
                           String note, String status, String rejectReason, String sellerName, boolean serialRequired,
                           String saleInvoiceNo, Long purchaseId, LocalDateTime createdAt, LocalDateTime decidedAt,
                           Integer version) { }

    /**
     * What the screen shows. The warehouse sees what was sent to it; any other seller the products it can send and its
     * own transfers. {@code warehouseNamed} false: MaxTheService has no warehouse, so nothing can be sent.
     */
    public record View(boolean warehouse, boolean warehouseNamed, List<Product> products, List<Transfer> transfers) { }
}
