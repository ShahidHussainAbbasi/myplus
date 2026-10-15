package com.myplus.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * HC-1a (V35) — a business type inside a product line. It decides, for a new tenant, its {@code organizations.type}
 * ({@link #orgType}: which application opens) and its {@code org.shape} ({@link #shape}: which features it starts with).
 */
@Entity
@Table(name = "business_type")
@Getter @Setter @NoArgsConstructor
public class BusinessType {

    @Id
    @Column(name = "type_key", length = 48)
    private String typeKey;

    @Column(name = "line_key", nullable = false, length = 32)
    private String lineKey;

    @Column(name = "name_key", nullable = false, length = 96)
    private String nameKey;

    /** English fallback; the screen shows {@link #nameKey}. */
    @Column(name = "name", nullable = false, length = 96)
    private String name;

    @Column(name = "description_key", length = 96)
    private String descriptionKey;

    @Column(name = "org_type", nullable = false, length = 32)
    private String orgType;

    @Column(name = "shape", nullable = false, length = 32)
    private String shape;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    public boolean isActive() { return "ACTIVE".equalsIgnoreCase(status); }
}
