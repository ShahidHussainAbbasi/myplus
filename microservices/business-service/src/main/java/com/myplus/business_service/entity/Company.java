package com.myplus.business_service.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "companies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Company {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String name;

    private String phone;
    private String email;
    private String address;
    private Long userId;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }

    public LocalDateTime getDated()                    { return createdAt; }
    public void          setDated(LocalDateTime dated) { this.createdAt = dated; }
    public LocalDateTime getUpdated()                      { return updatedAt; }
    public void          setUpdated(LocalDateTime updated) { this.updatedAt = updated; }
}
