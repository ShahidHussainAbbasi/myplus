package com.myplus.business_service.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SRA-2 — a SALES REP (booker / salesman — one role at Shafeeq), on ONE business's own list (V83).
 *
 * <p>A person, not a login: {@code userId} is optional and links the rep to a login when they have one. A rep who books
 * on paper never logs in, and the office keys their orders. Never deleted — an INACTIVE rep leaves the pickers and keeps
 * every sale and outlet history that names them.
 */
@Entity
@Table(name = "sales_rep")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SalesRep {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;        // tenant scope

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 40)
    private String phone;

    /** The login this rep signs in with, if any. Unique per business (V83). */
    @Column(name = "user_id")
    private Long userId;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_by")
    private Long createdBy;             // audit

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; if (status == null) status = "ACTIVE"; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
