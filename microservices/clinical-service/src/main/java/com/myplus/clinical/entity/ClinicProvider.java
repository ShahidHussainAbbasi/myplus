package com.myplus.clinical.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** HMS S2 — the clinic's own facts about a doctor (V2): the letter its tokens carry (A-007). */
@Entity
@Table(name = "clinic_provider")
@IdClass(ClinicProvider.Key.class)
@Getter @Setter @NoArgsConstructor
public class ClinicProvider {

    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Id
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "token_prefix", nullable = false, length = 4)
    private String tokenPrefix;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** H2 (V7): the login this doctor IS — set by the owner / an admin, never by the doctor; NULL = not linked. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "linked_at")
    private LocalDateTime linkedAt;

    @Column(name = "linked_by")
    private Long linkedBy;

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long organizationId;
        private Long providerId;

        public Key() {}
        public Key(Long organizationId, Long providerId) { this.organizationId = organizationId; this.providerId = providerId; }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(organizationId, k.organizationId) && Objects.equals(providerId, k.providerId);
        }
        @Override public int hashCode() { return Objects.hash(organizationId, providerId); }
    }
}
