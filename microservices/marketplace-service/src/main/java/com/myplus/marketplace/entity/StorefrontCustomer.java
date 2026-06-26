package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A storefront shopper account (slice 61, E4) — store-scoped (belongs to the store's org, NOT an org owner like the
 * staff auth-service). Password is BCrypt-hashed; sessionToken is an opaque login token. Unique per (org, email).
 */
@Entity
@Table(name = "storefront_customer",
        uniqueConstraints = @UniqueConstraint(name = "uq_sfc_org_email", columnNames = {"organization_id", "email"}),
        indexes = @Index(name = "idx_sfc_token", columnList = "session_token"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StorefrontCustomer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    private String email;
    private String name;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "session_token")
    private String sessionToken;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
