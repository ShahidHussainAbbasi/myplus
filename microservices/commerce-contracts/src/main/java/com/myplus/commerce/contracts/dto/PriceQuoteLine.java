package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One line of a pricing quote, in BOTH directions (slice b2b-P2 / requirement #10).
 *
 * <p>Request: {@code productId} + {@code quantity}. Response: those plus the resolved {@code unitPrice},
 * the {@code source} it came from and a human {@code reason}.
 *
 * <p>One type for both because they are the same line at two moments — and because a separate request DTO
 * would tempt a caller to send a price, which this contract deliberately never accepts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceQuoteLine {

    private Long productId;
    private BigDecimal quantity;

    // ── response only ──────────────────────────────────────────────────────────
    private BigDecimal unitPrice;
    /** CONTRACT (a named customer's rule) · TIER (their customer type's) · CATALOG (no rule applied). */
    private String source;
    private Long ruleId;
    private String reason;

    public static PriceQuoteLine of(Long productId, BigDecimal quantity) {
        PriceQuoteLine l = new PriceQuoteLine();
        l.setProductId(productId);
        l.setQuantity(quantity);
        return l;
    }
}
