package com.myplus.business_service.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SRA-1 — an area inside a {@link City}, in ONE business's own address list (V82). What a customer or supplier is
 * filed under ({@code customer.area_id}, {@code vender.area_id}), and what SRA-4 groups sales by.
 *
 * <p>Unique per business AND city, case-insensitively (V82's UNIQUE key): "Model Town" may exist in two cities, but
 * not twice in one. Never deleted — see {@link City}.
 */
@Entity
@Table(name = "area")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Area {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;        // tenant scope

    @Column(name = "city_id", nullable = false)
    private Long cityId;

    @Column(nullable = false, length = 80)
    private String name;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "user_id")
    private Long userId;                // creator (audit)

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; if (status == null) status = "ACTIVE"; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
