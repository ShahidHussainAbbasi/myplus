package com.myplus.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** RW-3 (V27) — one workspace: where a job lands, what opens it, which setting it needs. Data, not code. */
@Entity
@Table(name = "ui_workspace")
@Getter @Setter @NoArgsConstructor
public class UiWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module", nullable = false, length = 16)
    private String module;

    @Column(name = "ws_key", nullable = false, length = 48, unique = true)
    private String wsKey;

    @Column(name = "label_key", nullable = false, length = 96)
    private String labelKey;

    @Column(name = "icon", length = 48)
    private String icon;

    @Column(name = "url", nullable = false, length = 160)
    private String url;

    @Column(name = "codes", length = 512)
    private String codes;

    @Column(name = "capability", length = 64)
    private String capability;

    /** HC-1a (V34) — the business types (organization types) this tile is for; empty = every type. */
    @Column(name = "org_types", length = 160)
    private String orgTypes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
