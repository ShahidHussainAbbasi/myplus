package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Multi-location access grant (Pattern A). Records that a user may access a specific store/branch within an
 * organization, and their role there. The location registry itself is federated (business {@code store},
 * education {@code school}); this table is the central ACCESS layer keyed by a module-qualified location id,
 * so auth need not load domain rows — it only stores/serves what the owner/admin assigned. Propagated to
 * services via the JWT ({@code activeLocationId} / {@code accessibleLocationIds} / {@code roleAtLocation}).
 */
@Entity
@Table(name = "user_location_access", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "module", "location_id"})
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserLocationAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Which vertical the location belongs to: BUSINESS | EDUCATION | PHARMA | ... */
    @Column(nullable = false, length = 24)
    private String module;

    /** The domain store/branch id (business store.id or education school.id). FK-by-value, no cross-service FK. */
    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** Role at this location: OWNER | ADMIN | USER. */
    @Column(name = "role_at_location", nullable = false, length = 16)
    private String roleAtLocation;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
    }
}
