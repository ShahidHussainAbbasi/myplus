package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A per-tenant configuration override — one row per (organization, setting_key). This is the generic,
 * extensible settings store an owner edits from the Configuration screen: adding a new configurable policy
 * is a catalog entry ({@link com.myplus.education.config.SettingsCatalog}) + a read call, with NO schema
 * change. The catalog holds the DEFAULTS; this table holds only the values an owner has changed.
 *
 * value is stored as a String and interpreted per the catalog's declared type (BOOL / INT / TEXT / CHOICE).
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
