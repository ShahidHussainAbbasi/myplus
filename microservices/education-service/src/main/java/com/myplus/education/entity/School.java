package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "school")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "school_id", unique = true, nullable = false)
    private Long id;

    private String name;

    // Audit: which user created this row. Kept for "who did it", NOT for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to. All reads/writes are filtered by this.
    // Nullable during the userId->org migration; backfilled per-tenant via read-fallback.
    @Column(name = "organization_id")
    private Long organizationId;

    private String email;

    private String phone;

    private String address;

    @Column(name = "branch_name")
    private String branchName;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String status;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "schools_owners",
            joinColumns = @JoinColumn(name = "school_id", referencedColumnName = "school_id"),
            inverseJoinColumns = @JoinColumn(name = "owner_id", referencedColumnName = "owner_id"))
    private Set<Owner> owners;

    @PrePersist
    void prePersist() {
        dated = LocalDateTime.now();
        updated = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updated = LocalDateTime.now();
    }
}
