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

/**
 * RW-0b (V25) — one menu of the app, and the permission codes that unlock it. The pages name the KEY; which codes it
 * needs is this row, edited as data — never a list in a template. See hms-role-workspaces-design.md §8.
 */
@Entity
@Table(name = "ui_menu")
@Getter @Setter @NoArgsConstructor
public class UiMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "module", nullable = false, length = 16)
    private String module;

    @Column(name = "menu_key", nullable = false, length = 64, unique = true)
    private String menuKey;

    @Column(name = "parent_key", length = 64)
    private String parentKey;

    @Column(name = "label_key", length = 96)
    private String labelKey;

    /** Comma-separated = ANY of them unlocks the menu; null = everyone signed in. */
    @Column(name = "codes", length = 512)
    private String codes;

    /** The business setting(s) that must also be on (comma-separated = any); null = always. */
    @Column(name = "capability", length = 64)
    private String capability;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
