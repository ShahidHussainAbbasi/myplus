package com.myplus.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "aggregated_metrics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String metricName;

    private String dimension;

    @Column(nullable = false)
    private Double value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PeriodType periodType;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    private String serviceSource;

    private LocalDateTime computedAt;

    @PrePersist
    public void prePersist() {
        if (computedAt == null) computedAt = LocalDateTime.now();
    }

    public enum PeriodType { DAILY, WEEKLY, MONTHLY }
}
