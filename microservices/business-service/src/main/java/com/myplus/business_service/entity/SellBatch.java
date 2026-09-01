package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Which BATCH left the shelf for one sale line (slice b2b-P3b-2 = requirement #4).
 *
 * <p>Written from {@code StockReservationResponse.picks}, which inventory-service has always returned and
 * business-service has always discarded. The contract's own javadoc says the picks exist "so the sale (and
 * any pharmacy controlled-substance register) records exact batch traceability" — this is the record.
 *
 * <p><b>A child row, not a column on {@link Sell}.</b> FEFO legitimately splits one line across several
 * batches when the oldest cannot cover the quantity; a single {@code sell.batch_no} would keep one and drop
 * the rest, losing exactly the case traceability exists for — a part-shipped line during a recall.
 *
 * <p>Two questions this answers, hence two indexes: "what was on this invoice?" (by {@code sellId}) and
 * "which sales contained batch X?" (by org + batch), which is how a recall is actually executed.
 */
@Entity
@Table(name = "sell_batch")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellBatch {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sell_id", nullable = false)
    private Long sellId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "batch_no", length = 64)
    private String batchNo;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "quantity", precision = 19, scale = 3)
    private BigDecimal quantity;

    /**
     * #17 P3 — what one unit of this batch cost, stamped from the reservation that picked it.
     *
     * <p>This is what makes COGS the cost of the goods that actually LEFT. Stamped at write and never
     * re-derived: a purchase next week must not change last week's margin, which is exactly what
     * reading a current rate at report time would do.
     *
     * <p>Null for sales written before P3, where COGS falls back to the line's own cost snapshot.
     */
    @Column(name = "unit_cost", precision = 19, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
