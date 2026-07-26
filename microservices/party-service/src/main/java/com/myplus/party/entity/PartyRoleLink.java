package com.myplus.party.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * One role a party plays in one module — the denormalized index behind the cross-module contact view. Written on the
 * module's first bridge of a record (piggybacked on {@code upsert}) or by that module's backfill; read by
 * {@code GET /api/party/parties/{id}/roles}. Never holds domain data: {@code label} is a display caption only, so the
 * view can say "also a pharmacy patient" without party-service learning anything clinical.
 */
@Entity
@Table(name = "party_role_link", uniqueConstraints = {
        @UniqueConstraint(name = "uq_role_link", columnNames = {"party_id", "module", "role", "local_id"}) })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartyRoleLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    /** business | education | welfare | pharma */
    @Column(name = "module", length = 24, nullable = false)
    private String module;

    /** CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT */
    @Column(name = "role", length = 20, nullable = false)
    private String role;

    /** The owning module's own primary key for the record. */
    @Column(name = "local_id", nullable = false)
    private Long localId;

    @Column(name = "label", length = 160)
    private String label;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
