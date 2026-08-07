package com.myplus.inventory.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * inventory-service's per-tenant configuration overrides — one row per (organization, setting_key), backing the
 * shared common-settings engine via {@code JpaSettingsStore} (OMS O5a, table from {@code V7__org_setting.sql}).
 *
 * <p>The entity lives here, in a package the service already scans, so the shared library carries no
 * {@code @Entity} and no cross-module {@code @EntityScan} is needed.
 */
@Entity
@Table(name = "org_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uq_org_setting", columnNames = {"organization_id", "setting_key"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Column(name = "setting_value", length = 500)
    private String settingValue;

    private LocalDateTime updated;
}
