package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * business-service's per-tenant configuration overrides — one row per (organization, setting_key). Backs the
 * shared common-settings engine via {@code JpaSettingsStore}. The entity lives here (in the service's own
 * package, scanned normally) so the shared lib carries no @Entity and needs no cross-module @EntityScan.
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

    @Column(name = "setting_key", nullable = false, length = 100)
    private String settingKey;

    @Column(name = "setting_value", length = 500)
    private String settingValue;

    private LocalDateTime updated;
}
