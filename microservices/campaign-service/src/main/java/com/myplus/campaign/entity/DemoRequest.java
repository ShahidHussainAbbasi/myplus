package com.myplus.campaign.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Public "Book a Demo" lead (moved off the monolith's myplusdb). Persisted so a lead is never lost. */
@Entity
@Table(name = "demo_request")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;
    private String workEmail;
    private String company;
    private String country;
    private String phone;
    private String interest;
    private String companySize;

    @Column(length = 2000)
    private String message;

    private String timezone;
    private String preferredDate;
    private String locale;
    private String source;

    @Builder.Default
    private String status = "NEW";

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
