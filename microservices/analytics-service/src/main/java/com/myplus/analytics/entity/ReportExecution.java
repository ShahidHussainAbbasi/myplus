package com.myplus.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "report_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private ReportDefinition report;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Column(columnDefinition = "LONGTEXT")
    private String resultData;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long createdBy;

    public enum Status { RUNNING, COMPLETED, FAILED }
}
