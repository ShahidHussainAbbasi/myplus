package com.myplus.education.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "school")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class School {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "school_id", unique = true, nullable = false)
    private Long id;

    private String name;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String email;

    private String phone;

    private String address;

    @Column(name = "branch_name")
    private String branchName;

    @Column(updatable = false)
    private LocalDateTime dated;

    private LocalDateTime updated;

    private String status;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "schools_owners",
            joinColumns = @JoinColumn(name = "school_id", referencedColumnName = "school_id"),
            inverseJoinColumns = @JoinColumn(name = "owner_id", referencedColumnName = "owner_id"))
    private Set<Owner> owners;

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
