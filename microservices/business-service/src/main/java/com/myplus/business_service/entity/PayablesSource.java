package com.myplus.business_service.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FP-4b — where this tenant's supplier screens read from (V74). No row = BUSINESS. */
@Entity
@Table(name = "payables_source")
@Getter @Setter
public class PayablesSource {

    public static final String BUSINESS = "BUSINESS", FINANCE = "FINANCE";

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "switched_by")
    private Long switchedBy;

    @Column(name = "switched_at", nullable = false)
    private LocalDateTime switchedAt;

    @Column(name = "business_due", precision = 19, scale = 2)
    private BigDecimal businessDue;

    @Column(name = "finance_purchase_net", precision = 19, scale = 2)
    private BigDecimal financePurchaseNet;

    @Column(name = "gl_difference", precision = 19, scale = 2)
    private BigDecimal glDifference;
}
