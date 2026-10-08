package com.myplus.marketplace.multiseller.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** MKT-1d — one platform-wide marketplace setting (V27). Belongs to no tenant; only the operator writes it. */
@Entity
@Table(name = "mkt_platform_setting")
@Getter
@Setter
@NoArgsConstructor
public class MarketplacePlatformSetting {

    public static final String DEFAULT_SORT = "public.defaultSort";
    /** MKT-1e — minutes a MERCHANT seller has to accept an order (source §10; default 5). */
    public static final String ACCEPT_MINUTES = "checkout.acceptMinutes";
    /** MKT-1f (R-MKT-14): what a change-of-mind return costs the customer — the rider's pickup — in rupees. */
    public static final String CHANGE_OF_MIND_FEE = "return.changeOfMindFee";
    /** MKT-1g — N in T+N: business days after the return window closes before a line is payable (default 1). */
    public static final String SETTLEMENT_T_PLUS_DAYS = "settlement.tPlusDays";
    /** MKT-1g — the organisation whose ledger takes the commission (the operator's books); its user is updated_by. */
    public static final String SETTLEMENT_BOOKS_ORG = "settlement.booksOrg";
    /** MKT-2a — "true" lets one checkout buy from several sellers (a parent order with one part per seller). Default off. */
    public static final String MULTI_SELLER = "checkout.multiSeller";
    /** MKT-2b — "true" moves a part a seller could not fulfil to another seller, or asks the customer. Default off. */
    public static final String SHORTAGE_REROUTE = "shortage.reroute";
    /** MKT-2c — the operator's test switch: "{sellerOrg}:{delayMs}" makes that seller's holds slow. Read only when
     *  {@code mkt.routing.test-switch=true}; empty or unparseable reads as off. */
    public static final String ROUTING_SLOW_SELLER = "routing.testSlowSeller";

    @Id
    @Column(name = "setting_key", nullable = false, length = 64)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String settingValue;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
