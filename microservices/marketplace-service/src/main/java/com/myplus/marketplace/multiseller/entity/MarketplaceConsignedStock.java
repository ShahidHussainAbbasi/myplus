package com.myplus.marketplace.multiseller.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MKT-5b (R20.5) — one movement in the consigned stock ledger (V41). What the warehouse holds for a consignor, product
 * by product, is the sum of {@link #getQuantity()}: + into the warehouse, - out of it. Rows are never edited; a
 * correction is another movement.
 */
@Entity
@Table(name = "mkt_consigned_stock")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceConsignedStock {

    /** Received at the warehouse from the consignor (5b). */
    public static final String RECEIVED = "RECEIVED";
    /** MKT-5c — sold by the warehouse: the owner's sale to MaxTheService and MaxTheService's purchase, one per order line and lot. */
    public static final String SOLD = "SOLD";
    /** MKT-5c — a sale to MaxTheService that had to be voided (quantity 0): the next try is a new attempt with new keys. */
    public static final String VOIDED = "VOIDED";

    /** MKT-5d — units a count found missing, taken out once the owner wrote them off or a claim for them was approved. */
    public static final String LOST = "LOST";

    /** MKT-5e — units back with their owner: written when it confirms it has them (doc_no = the return, RT-…). */
    public static final String RETURNED = "RETURNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    @Column(name = "consignor_org_id", nullable = false)
    private Long consignorOrgId;

    @Column(name = "consignee_org_id", nullable = false)
    private Long consigneeOrgId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "consignor_product_id", nullable = false)
    private Long consignorProductId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "movement", nullable = false, length = 16)
    private String movement;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitValue;

    @Column(name = "serials", length = 2000)
    private String serials;

    @Column(name = "transfer_id")
    private Long transferId;

    @Column(name = "hold_key", length = 64)
    private String holdKey;

    /** MKT-5c (V42): the order line a SOLD or VOIDED row belongs to; 0 on a RECEIVED row. */
    @Column(name = "order_line_id", nullable = false)
    private Long orderLineId = 0L;

    /** MKT-5c (V42): which try of the same line and lot; 0 on a RECEIVED row. */
    @Column(name = "attempt", nullable = false)
    private Integer attempt = 0;

    /** MKT-5c (V42): the owner's invoice to MaxTheService for a SOLD row. */
    @Column(name = "sale_invoice_no", length = 64)
    private String saleInvoiceNo;

    /** MKT-5c (V42): the warehouse's purchase from the owner for a SOLD row. */
    @Column(name = "purchase_id")
    private Long purchaseId;

    /** MKT-5c (V42): what MaxTheService owes the owner a unit for a SOLD row: the selling price less the commission. */
    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    /** MKT-5d (V43): the count write-off or claim a LOST row resolves (CC-000001-W1, SC-000001), MKT-5e the return of a RETURNED row (RT-000001); empty otherwise. */
    @Column(name = "doc_no", nullable = false, length = 20)
    private String docNo = "";

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
