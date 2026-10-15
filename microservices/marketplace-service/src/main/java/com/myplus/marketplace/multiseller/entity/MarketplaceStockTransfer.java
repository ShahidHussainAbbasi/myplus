package com.myplus.marketplace.multiseller.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MKT-3c — stock a seller sends to the MaxTheService warehouse, which MaxTheService buys (V37).
 *
 * <p>{@link #OFFERED} by the seller → ({@link #RECEIVING}) → {@link #RECEIVED} by the warehouse (the seller's sale and the warehouse's purchase
 * are written then, together) or {@link #REJECTED} by the warehouse, or {@link #CANCELLED} by the seller while still
 * offered. The stock itself only ever lives in the two tenants' books; this row links the two documents.
 */
@Entity
@Table(name = "mkt_stock_transfer")
@Getter
@Setter
@NoArgsConstructor
public class MarketplaceStockTransfer {

    public static final String OFFERED = "OFFERED";
    /** Claimed by a Receive that is writing the books; a retry carries on from here with the same keys. */
    public static final String RECEIVING = "RECEIVING";
    public static final String RECEIVED = "RECEIVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_org_id", nullable = false)
    private Long sellerOrgId;

    @Column(name = "warehouse_org_id", nullable = false)
    private Long warehouseOrgId;

    @Column(name = "mkt_product_id", nullable = false)
    private Long mktProductId;

    @Column(name = "seller_product_id", nullable = false)
    private Long sellerProductId;

    @Column(name = "warehouse_product_id", nullable = false)
    private Long warehouseProductId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "serials", length = 2000)
    private String serials;

    @Column(name = "sale_invoice_no", length = 40)
    private String saleInvoiceNo;

    @Column(name = "purchase_id")
    private Long purchaseId;

    /**
     * Part of the idempotency keys of the sale and the purchase. A Receive whose outcome is unknown (a timeout) is
     * retried with the SAME keys, so it replays and never books twice. A Receive whose sale was reversed because the
     * purchase was refused moves to the next attempt: the old key would replay the voided invoice.
     */
    @Column(name = "attempt", nullable = false)
    private Integer attempt = 0;

    @Column(name = "offered_by_user_id")
    private Long offeredByUserId;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /** "ST-000012": what both sides see and quote. */
    public String getTransferNo() {
        return id == null ? null : String.format("ST-%06d", id);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
