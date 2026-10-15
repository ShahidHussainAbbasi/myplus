package com.myplus.commerce.contracts.dto;

import lombok.*;

import java.math.BigDecimal;

/** MKT-3c — what the buyer's books recorded for a {@link PurchaseRecordRequest}. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PurchaseRecordResult {

    /** The purchase row. */
    private Long purchaseId;

    /** The supplier record it is owed to, in the buyer's books. */
    private Long vendorId;

    /** The bill total the server computed (the request carries no total). */
    private BigDecimal totalAmount;
}
