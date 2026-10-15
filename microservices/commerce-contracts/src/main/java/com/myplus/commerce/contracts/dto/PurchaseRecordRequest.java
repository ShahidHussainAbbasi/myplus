package com.myplus.commerce.contracts.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * MKT-3c — goods bought from another tenant, put into the buyer's books by a service acting for the buyer.
 *
 * <p>The purchase seam's request: one product per bill line, exactly as {@code /addPurchase} takes it. The buyer is the
 * AUTHENTICATED org (business-service re-checks {@link #organizationId} against it); the supplier is named, and found
 * or created inside the buyer's own supplier list. Nothing is paid: the whole bill is owed to the supplier.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PurchaseRecordRequest {

    /** Required: a retry with the same key returns the same purchase, never a second stock-in or payable. */
    private String idempotencyKey;

    /** The buyer; must equal the authenticated org when given. */
    private Long organizationId;

    /** The supplier as the buyer's books name it. Found by name (case-insensitive) or created. */
    private String vendorName;

    /** The buyer's own product. */
    private Long productId;

    private BigDecimal quantity;

    /** Cost of one unit, before tax. */
    private BigDecimal unitCost;

    /** Comma-separated serial numbers (IMEIs), one per unit, for a serial-tracked product. */
    private String serials;

    /** The supplier's invoice number, kept as the bill number. */
    private String billNo;
}
