package com.myplus.agriculture.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A per-tenant configuration override — one row per (organization, setting_key), backing the shared
 * common-settings engine onto agriculture-service's own {@code org_setting} table. Holds only the values an
 * owner has changed from the code-defined catalog default
 * ({@link com.myplus.agriculture.config.AgricultureSettingsCatalog}).
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
    private Long organizationId;   // tenant scope

    @Column(name = "user_id")
    private Long userId;           // audit: who last changed it

    @Column(name = "setting_key", nullable = false, length = 100)
    private String settingKey;

    @Column(name = "setting_value", length = 500)
    private String settingValue;

    private LocalDateTime updated;
}
