package com.myplus.analytics.dto;

import com.myplus.analytics.entity.ReportExecution;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportExecutionDTO {
    private Long id;
    private Long reportId;
    private ReportExecution.Status status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String resultData;
    private String errorMessage;
    private Long createdBy;
}
