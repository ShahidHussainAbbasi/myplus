package com.myplus.inventory.entity;

import lombok.*;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_alerts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StockAlert {

    public enum AlertType { LOW_STOCK, EXPIRY_NEAR, OUT_OF_STOCK }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    private AlertType alertType;

    @Column(length = 500)
    private String message;

    @Builder.Default
    private Boolean isRead = false;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
