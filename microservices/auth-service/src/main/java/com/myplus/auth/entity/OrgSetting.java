package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * C3c — per-tenant configuration overrides owned by auth-service.
 *
 * <h3>Why capabilities live HERE and not in each service</h3>
 * A capability is a property of the TENANT, not of a service. Storing it per-service gave N answers to one
 * question: an owner switched {@code rxRequired} off, the row landed in business-service's table, pharma-service
 * read its own table, found nothing, defaulted to ON, and the guard never fired. Correct code that could not
 * possibly work.
 *
 * <p>auth-service already owns the tenant — {@code Organization} carries its type, plan, trial and entry cap,
 * and those already travel to every service as JWT claims. Capabilities are the same kind of fact and now take
 * the same road, so there is exactly one store, one screen and one answer.
 *
 * <p>Same table shape as the six services that already have one (business V26, marketplace V12, inventory V7),
 * so the shared common-settings engine binds to it unchanged.
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
