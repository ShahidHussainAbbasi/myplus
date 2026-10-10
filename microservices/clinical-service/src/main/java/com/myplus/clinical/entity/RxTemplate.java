package com.myplus.clinical.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * HMS S3b-2 — a prescription template of the clinic (V6). {@code nameKey} carries the per-clinic unique name and is
 * NULL once retired, so a retired template frees its name. Never deleted.
 */
@Entity
@Table(name = "rx_template")
@Getter
@Setter
@NoArgsConstructor
public class RxTemplate {

    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "name_key", length = 80)
    private String nameKey;

    @Column(name = "status", nullable = false, length = 16)
    private String status = ACTIVE;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;
}
