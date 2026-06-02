package com.myplus.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "dashboard_widgets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WidgetType widgetType;

    @Column(nullable = false)
    private String title;

    private String dataSource;

    @Column(columnDefinition = "TEXT")
    private String config;

    @Builder.Default
    private int position = 0;

    @Builder.Default
    private boolean isActive = true;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public enum WidgetType { COUNTER, BAR_CHART, LINE_CHART, PIE_CHART, TABLE }
}
