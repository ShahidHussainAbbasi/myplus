package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;

@Entity
@Table(name = "warehouses")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Warehouse {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String location;
    private String address;
    private Float capacity;
    private Long managerId;
}
