package com.myplus.business_service.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A physical POS location (store) within a tenant. Multi-location Pattern A: transactions are scoped to a
 * store; a single-store business simply has one row. Access to a store is granted per user in auth-service
 * ({@code user_location_access}); this table is the store registry business-service owns.
 */
@Entity
@Table(name = "store")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Store {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 40)
    private String code;

    @Column(length = 500)
    private String address;

    @Column(length = 40)
    private String phone;

    @Column(name = "user_id")
    private Long userId;                // creator (audit)

    @Column(name = "organization_id")
    private Long organizationId;        // tenant scope

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; if (status == null) status = "ACTIVE"; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
