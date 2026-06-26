package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A held (parked) sale (POS R10, slice 40): the cashier's in-progress cart stored to resume later — no stock move
 * or invoice until it's completed. {@code cartJson} is the serialized checkout payload (same shape addSell takes).
 * Org + cashier scoped.
 */
@Entity
@Table(name = "parked_sale", indexes = @Index(name = "idx_parked_org_user", columnList = "organization_id,user_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ParkedSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;                // the cashier who parked it

    private String label;              // e.g. customer name / a note

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(precision = 19, scale = 2)
    private BigDecimal total;

    @Lob
    @Column(name = "cart_json", columnDefinition = "TEXT")
    private String cartJson;           // serialized checkout payload (rebuilt into the cart on resume)

    @Column(name = "parked_at")
    private LocalDateTime parkedAt;

    @PrePersist
    void onCreate() { if (parkedAt == null) parkedAt = LocalDateTime.now(); }
}
