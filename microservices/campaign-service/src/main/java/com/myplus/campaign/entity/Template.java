package com.myplus.campaign.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String htmlContent;

    @Column(columnDefinition = "TEXT")
    private String textContent;

    private Long createdBy;

    @Builder.Default
    private boolean isActive = true;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Type { EMAIL, SMS }
}
