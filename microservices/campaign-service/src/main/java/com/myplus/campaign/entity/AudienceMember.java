package com.myplus.campaign.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "audience_members")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudienceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audience_id")
    private Audience audience;

    private Long userId;
    private String email;
    private String phone;
    private String firstName;
    private String lastName;

    @Builder.Default
    private boolean isActive = true;

    private LocalDateTime subscribedAt;
    private LocalDateTime unsubscribedAt;

    @PrePersist
    public void prePersist() {
        if (subscribedAt == null) subscribedAt = LocalDateTime.now();
    }
}
