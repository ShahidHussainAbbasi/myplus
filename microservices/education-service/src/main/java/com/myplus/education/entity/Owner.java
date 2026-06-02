package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "owner", uniqueConstraints = {@UniqueConstraint(columnNames = "name")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "owner_id", unique = true, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false)
    private String name;

    private String email;

    private String mobile;

    private String address;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String status;

    @ManyToMany(mappedBy = "owners")
    private Set<School> schools;

    @PrePersist
    void prePersist() {
        dated = LocalDateTime.now();
        updated = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updated = LocalDateTime.now();
    }
}
