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
