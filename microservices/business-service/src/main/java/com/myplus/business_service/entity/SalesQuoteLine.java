package com.myplus.business_service.entity;

import java.io.Serializable;
import java.math.BigDecimal;

import jakarta.persistence.*;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * B2B-P4b — one line of a sales quote: a SNAPSHOT of what was offered, at the price it was offered for.
 *
 * <p>{@code unitPrice} and {@code priceReason} are captured when the quote is priced and never recomputed. The
 * customer is holding a document with these numbers on it; re-deriving them at conversion time — after a price
 * rule changed, say — would quietly bill them something other than what they accepted.
 */
@Data
// Same recursion, two more ways: Lombok's generated equals/hashCode/toString would walk the `quote`
// back-reference into the parent and straight back down its lines. Excluded here rather than discovered later
// as a StackOverflowError inside a log statement.
@EqualsAndHashCode(exclude = "quote")
@ToString(exclude = "quote")
@Entity
@Table(name = "sales_quote_line")
public class SalesQuoteLine implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The owning quote. {@code @JsonIgnore} because this back-reference exists for JPA, not for the wire:
     * serialising it walks {@code quote → lines → quote → …} and produced a 151 KB reply for a ONE-line quote
     * before the proxy gave up. Jackson's cycle detection does not catch it — each level is a fresh object, so
     * it simply nests until something downstream breaks. The client already has the quote; the line does not
     * need to carry it back.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_id")
    private SalesQuote quote;

    @Column(name = "product_id")
    private Long productId;

    /** Denormalised so an old quote still prints correctly after a product is renamed or deactivated. */
    @Column(name = "product_name")
    private String productName;

    private Float quantity;

    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    /**
     * WHY this line got this price — "Wholesale price −12%", "Contract price". A snapshot of the human reason,
     * not the rule id: rules get edited and deleted, and a quote must still explain itself afterwards. Same
     * rule as {@code Sell.priceReason} (B2B-P2 #10).
     */
    @Column(name = "price_reason", length = 64)
    private String priceReason;

    /** Per-line discount, distinct from the document-level trade discount on the quote header. */
    @Column(name = "discount", precision = 19, scale = 2)
    private BigDecimal discount;

    @Column(name = "line_total", precision = 19, scale = 2)
    private BigDecimal lineTotal;
}
