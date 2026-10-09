package com.myplus.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
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

    /** AN-1 — DECIMAL(19,2) (V3; was a double): a money metric must add up to the books to the cent. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PeriodType periodType;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    private String serviceSource;

    @Column(name = "organization_id")
    private Long organizationId;   // tenant scope — a metric belongs to the org it was computed for

    private LocalDateTime computedAt;

    @PrePersist
    public void prePersist() {
        if (computedAt == null) computedAt = LocalDateTime.now();
    }

    public enum PeriodType { DAILY, WEEKLY, MONTHLY }
}
