package com.myplus.auth.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /**
     * The owner. <b>{@code @ManyToOne}, deliberately</b> — a user has one row per SESSION/device, not one
     * row in total.
     *
     * <p>This was {@code @OneToOne}, which put a UNIQUE key on {@code user_id} and meant every login
     * overwrote the user's single token: the previously signed-in device could no longer refresh, so
     * ~15 minutes later (the access-token lifetime) all of its calls began to 401. Two devices is the
     * normal case for this platform — a till and a phone, or a counter and a back-office tab.
     * See {@code V6__refresh_token_per_session.sql}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant expiryDate;
}
