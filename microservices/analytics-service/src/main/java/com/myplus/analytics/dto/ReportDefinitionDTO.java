package com.myplus.analytics.dto;

import com.myplus.analytics.entity.ReportDefinition;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDefinitionDTO {
    private Long id;
    @NotNull
    @Size(min = 1, max = 200)
    private String name;
    private String description;
    @NotNull
    private ReportDefinition.Type type;
    @NotNull
    private ReportDefinition.ScheduleType scheduleType;
    private LocalDateTime lastRunAt;
    private Long createdBy;
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
