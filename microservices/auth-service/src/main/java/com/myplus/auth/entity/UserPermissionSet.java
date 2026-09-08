package com.myplus.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Which set a member is on. ONE set, deliberately — not many.
 *
 * <p>"Which of these five sets won?" is a question nobody can answer at a counter, and a model whose
 * effective permissions cannot be read off the screen is a model an owner stops trusting. The exception
 * belongs in a per-user override (phase 3), where it is visibly an exception, rather than in a second set
 * that quietly unions with the first.
 *
 * <p>An OWNER has no row here at all: their access is implicit, so no edit to any set can lock them out
 * of their own shop (design G-4).
 */
@Entity
@Table(name = "user_permission_set")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserPermissionSet {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "set_id", nullable = false)
    private Long setId;
}
