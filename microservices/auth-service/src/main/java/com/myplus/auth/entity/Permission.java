package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One thing a member can DO — the grain of the whole permission model.
 *
 * <p>{@code area.action}: {@code sale.create}, {@code team.create}, {@code report.export}. Actions, not
 * menus, and that distinction is the design's foundation: the owner's own example was "when Jawwad tries
 * to create a user, tell him he is not allowed" — creating a user is an action. Hide the Team menu and
 * {@code POST /team/users} still answers. So permissions gate actions and the MENU IS DERIVED from them,
 * which is what stops the screen and the enforcement ever disagreeing.
 *
 * <h3>{@code implies} is data, not code</h3>
 * That {@code sale.create} needs {@code product.view} is a fact about the SCREENS — a sale cannot be rung
 * up against a product list the member may not read. Facts about screens belong beside them, where the
 * person adding a screen will see them, not buried in a service they will never open.
 */
@Entity
@Table(name = "permission")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Permission {

    /** {@code area.action} — also the authority string, so hasAuthority('sale.create') just works. */
    @Id
    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "area", nullable = false, length = 32)
    private String area;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    /** What the OWNER reads in the matrix — "Ring up a sale", never "sale.create". */
    @Column(name = "label", nullable = false, length = 128)
    private String label;

    /** Comma-separated codes this one also grants. See the class note: closure is data. */
    @Column(name = "implies", length = 512)
    private String implies;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
