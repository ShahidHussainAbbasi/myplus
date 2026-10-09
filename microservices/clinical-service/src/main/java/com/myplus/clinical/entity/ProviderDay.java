package com.myplus.clinical.entity;

import java.io.Serializable;
import java.time.LocalDate;
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

/** HMS S2 — one day's exception to a doctor's usual daily limit (V2): another limit, or closed. */
@Entity
@Table(name = "provider_day")
@IdClass(ProviderDay.Key.class)
@Getter @Setter @NoArgsConstructor
public class ProviderDay {

    @Id
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Id
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Id
    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** Null = the doctor's usual limit; 0 or less = no limit today. */
    @Column(name = "cap")
    private Integer cap;

    @Column(name = "closed", nullable = false)
    private Boolean closed = Boolean.FALSE;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long organizationId;
        private Long providerId;
        private LocalDate visitDate;

        public Key() {}
        public Key(Long organizationId, Long providerId, LocalDate visitDate) {
            this.organizationId = organizationId; this.providerId = providerId; this.visitDate = visitDate;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(organizationId, k.organizationId)
                    && Objects.equals(providerId, k.providerId) && Objects.equals(visitDate, k.visitDate);
        }
        @Override public int hashCode() { return Objects.hash(organizationId, providerId, visitDate); }
    }
}
