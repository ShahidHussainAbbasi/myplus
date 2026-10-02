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

    /**
     * DR-1 — the phone as a MATCH key: last 10 digits ({@link com.myplus.common.web.PartyKeys#phoneKey}), so every
     * spelling the mobile validator accepts is one partner. DERIVED from {@code contact} on every write (see the
     * lifecycle hooks below) — never set directly, so no writer can leave it out of step.
     */
    @Column(name = "contact_key", length = 16)
    private String contactKey;

    /** DR-1 — CNIC / NTN digits ({@link com.myplus.common.web.PartyKeys#taxKey}). The strongest match key. */
    @Column(name = "tax_key", length = 32)
    private String taxKey;

    /**
     * DR-1 — the phone a partner HAS when its raw text cannot be stored: same number as another partner but a different
     * CNIC / NTN (two legal identities), and the raw UNIQUE (org, contact) forbids a second copy of the text. Without
     * this the newcomer had no phone key at all, so the owner's "possible duplicates" list could never show the pair.
     */
    @Transient
    private String phoneWithoutContact;

    @Column(name = "address")
    private String address;

    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * B2B account hierarchy (Phase 4a) — company → branch → contact. Identity STRUCTURE, which is exactly what
     * this service owns; the credit and AR that hang off it stay in the module that owns them.
     *
     * <p>Null = a root (a company, or a plain individual). Guarded on write in {@code PartyService}: the parent
     * must be in the SAME organization (a foreign parent would be a tenancy hole), the graph must stay acyclic,
     * and depth is capped at COMPANY → BRANCH → CONTACT.
     */
    @Column(name = "parent_party_id")
    private Long parentPartyId;

    /**
     * Where this party sits in that hierarchy: {@code COMPANY | BRANCH | CONTACT | INDIVIDUAL}.
     * {@code INDIVIDUAL} is the default and describes every party that existed before Phase 4a — a walk-in or a
     * one-site trade customer that is not part of any group. An INDIVIDUAL may neither have nor be a parent.
     */
    @Builder.Default
    @Column(name = "account_level", length = 12)
    private String accountLevel = "INDIVIDUAL";

    @Builder.Default
    @Column(name = "active")
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now();
        this.contactKey = deriveContactKey();
    }
    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
        this.contactKey = deriveContactKey();
    }

    /** From the stored contact; else the phone held back by {@link #phoneWithoutContact}; else the key already held (a
     *  partner created that way keeps its key across later edits that do not touch the contact). */
    private String deriveContactKey() {
        if (contact != null) return com.myplus.common.web.PartyKeys.phoneKey(contact);
        if (phoneWithoutContact != null) return com.myplus.common.web.PartyKeys.phoneKey(phoneWithoutContact);
        return contactKey;
    }
}
