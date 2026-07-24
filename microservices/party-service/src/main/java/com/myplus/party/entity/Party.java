package com.myplus.party.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * A party/contact: the shared identity of a person or organisation, referenced by every module via {@code id}
 * (the partyId). Owns ONLY common identity — never domain data (AR, Rx, fees, loyalty stay in the owning module,
 * keyed by this id). De-dup key per tenant is {@code (organization_id, contact)}; {@code partyType} records the
 * PRIMARY role but a party can play several across modules (tracked by each module's bridge, not here).
 */
@Entity
@Table(name = "party", uniqueConstraints = {
        @UniqueConstraint(name = "uq_party_org_contact", columnNames = {"organization_id", "contact"}) })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Party {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;                 // audit: who created it

    /** Primary role: CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT | OTHER (superset of finance's PartyType). */
    @Column(name = "party_type", length = 20)
    private String partyType;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "contact", length = 64)
    private String contact;              // phone/mobile — the primary de-dup key within an org

    @Column(name = "email")
    private String email;

    @Column(name = "address")
    private String address;

    @Column(name = "notes", length = 500)
    private String notes;

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
