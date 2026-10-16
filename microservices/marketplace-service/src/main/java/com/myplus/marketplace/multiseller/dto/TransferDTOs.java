package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MKT-3c — stock a seller sends to the MaxTheService warehouse, which MaxTheService buys; MKT-5b — or consigns to it
 * under an active agreement, keeping ownership ({@code kind} BUY | CONSIGN).
 */
public final class TransferDTOs {

    private TransferDTOs() { }

    /** A product the seller sells that MaxTheService also carries: the only ones it can send. */
    public record Product(Long mktProductId, String name) { }

    /** {@code kind} BUY (null, the 3c default) or CONSIGN; for CONSIGN {@code unitPrice} is the value a unit declared. */
    public record OfferRequest(Long mktProductId, Integer quantity, BigDecimal unitPrice, String note, String kind) {
        public OfferRequest(Long mktProductId, Integer quantity, BigDecimal unitPrice, String note) {
            this(mktProductId, quantity, unitPrice, note, null);
        }
    }

    /** Cancel (the seller), Reject with a reason or Receive with the serials (the warehouse). */
    public record DecisionRequest(String transferNo, Integer version, String reason, List<String> serials) { }

    public record Transfer(String transferNo, String productName, int quantity, BigDecimal unitPrice, BigDecimal total,
                           String note, String status, String rejectReason, String sellerName, boolean serialRequired,
                           String saleInvoiceNo, Long purchaseId, LocalDateTime createdAt, LocalDateTime decidedAt,
                           Integer version, String kind, String agreementNo) { }

    /** MKT-5b — consigned stock the warehouse holds: per owner and product, the units and their declared value. */
    public record Held(String consignorName, Long mktProductId, String productName, long units, BigDecimal value) { }

    /**
     * What the screen shows. The warehouse sees what was sent to it; any other seller the products it can send and its
     * own transfers. {@code warehouseNamed} false: MaxTheService has no warehouse, so nothing can be sent.
     * {@code consignUnder}: the seller's active consignment agreement (CA-…), or null when it cannot consign.
     * {@code held}: consigned stock at the warehouse (the seller's own, or, for the warehouse, every owner's).
     */
    public record View(boolean warehouse, boolean warehouseNamed, List<Product> products, List<Transfer> transfers,
                       String consignUnder, List<Held> held) {
        public View(boolean warehouse, boolean warehouseNamed, List<Product> products, List<Transfer> transfers) {
            this(warehouse, warehouseNamed, products, transfers, null, List.of());
        }
    }
}
