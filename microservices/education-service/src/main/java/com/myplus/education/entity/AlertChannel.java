package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_channel")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AlertChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_channel_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel", nullable = false)
    private String c;

    @Column(name = "channel_name")
    private String cn;

    @Column(name = "user_type")
    private String ut;

    @Column(name = "dated")
    private LocalDateTime dt;

    @Column(name = "status")
    private String s;

    @PrePersist
    void prePersist() {
        if (dt == null) dt = LocalDateTime.now();
    }
}
