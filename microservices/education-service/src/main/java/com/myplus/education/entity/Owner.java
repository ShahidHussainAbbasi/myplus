package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
// Owner name is unique PER TENANT, not globally — one tenant's "John" must not block another's.
@Table(name = "owner", uniqueConstraints = {
        @UniqueConstraint(name = "uq_owner_org_name", columnNames = {"organization_id", "name"})})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "owner_id", unique = true, nullable = false)
    private Long id;

    // Audit: which user created this row. Not used for data scoping.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Tenant scope: which organization this row belongs to.
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    private String email;

    private String mobile;

    private String address;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String status;

    @ManyToMany(mappedBy = "owners")
    private Set<School> schools;

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
