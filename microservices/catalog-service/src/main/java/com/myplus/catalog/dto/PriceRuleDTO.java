package com.myplus.catalog.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A pricing rule as the Price Rules screen sees it (slice b2b-P2 / requirement #10). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PriceRuleDTO {
    private Long id;
    /** CUSTOMER | TYPE */
    private String scope;
    private Long customerId;
    /** WALK_IN | RETAILER | WHOLESALE | VIP — Phase 0's Customer.customerType. */
    private String customerType;
    /** PRODUCT | CATEGORY */
    private String target;
    private Long productId;
    private Long categoryId;
    /** FIXED | PERCENT */
    private String mode;
    private BigDecimal value;
    private Integer priority;
    private Boolean active;
    private LocalDate startsOn;
    private LocalDate endsOn;
    /** Read-only, for the list screen: the product/category this rule points at. */
    private String targetName;
}
