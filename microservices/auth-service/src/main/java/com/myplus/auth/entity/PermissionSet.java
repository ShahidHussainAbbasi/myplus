package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A named bundle of permissions — "Cashier", "Storekeeper" — and the unit this model is built on.
 *
 * <h3>Why sets rather than per-user checkboxes</h3>
 * Per-user is simpler for the two users a shop starts with and worse for the twenty it grows into:
 * twenty checklists that drift, and no way to answer "make the new hire like Ahmed" in one action. Every
 * serious product in this category — Square's permission sets, Zoho's roles, Odoo's groups — converged on
 * the same answer, and the owner ruled for it here.
 *
 * <p>{@code organizationId} NULL means a BUILT-IN template every tenant sees. Two of them, Standard and
 * Administrator, are not examples but a CONTRACT: they reproduce exactly what a member could already do
 * before permissions existed, so the deploy that introduces this feature changes nothing on anyone's
 * screen.
 */
@Entity
@Table(name = "permission_set")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PermissionSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = a built-in template shared by every tenant. Otherwise the tenant that owns it. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    /**
     * WHICH ROWS this set may read — a different question from which actions, and the one an owner is
     * most likely to conflate with the matrix (design G-3).
     *
     * <p>{@code OWN} = only records this member created; {@code ALL} = everything in the shop. Ticking
     * "See sales" cannot answer it, which is exactly why it is a control of its own rather than a
     * reading someone has to guess.
     */
    @Column(name = "scope", nullable = false, length = 8)
    private String scope;

    /** A built-in cannot be edited or deleted — a tenant DUPLICATES it and edits the copy. */
    @Column(name = "is_builtin", nullable = false)
    private boolean builtin;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** The module this set belongs to — a school never offers a shop's set, or the reverse. */
    @Column(name = "module", nullable = false, length = 16)
    private String module;
}
