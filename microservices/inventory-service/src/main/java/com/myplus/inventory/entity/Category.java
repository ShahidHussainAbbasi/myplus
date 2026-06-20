package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parentCategory;

    // Tenant scope (slice 33, Phase 4.5) — nullable; ddl-auto/Flyway V2 adds them.
    private Long organizationId;
    private Long userId;
    private String userType;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
