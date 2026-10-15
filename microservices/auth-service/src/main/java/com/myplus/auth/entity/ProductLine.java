package com.myplus.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** HC-1a (V35) — a product line (Business, Healthcare, ...): the first question of the New tenant form. Data, not code. */
@Entity
@Table(name = "product_line")
@Getter @Setter @NoArgsConstructor
public class ProductLine {

    @Id
    @Column(name = "line_key", length = 32)
    private String lineKey;

    @Column(name = "name_key", nullable = false, length = 96)
    private String nameKey;

    /** English fallback; the screen shows {@link #nameKey}. */
    @Column(name = "name", nullable = false, length = 96)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** ACTIVE = offered; INACTIVE = seeded so the list is complete, not offered yet. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
