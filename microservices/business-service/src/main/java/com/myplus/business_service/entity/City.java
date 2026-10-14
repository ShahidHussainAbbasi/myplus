package com.myplus.business_service.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SRA-1 — a city in ONE business's own address list (V82). Owner-maintained data: nothing is seeded and nothing is
 * deleted — an INACTIVE city leaves the pickers but still names the customers filed under it.
 *
 * <p>The name is unique per business case-insensitively, by the column's collation (V82's UNIQUE key), so the
 * service only trims; a second "khanpur" is refused by the database even under two clicks at once.
 */
@Entity
@Table(name = "city")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class City {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;        // tenant scope

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
