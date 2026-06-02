package com.myplus.analytics.dto;

import com.myplus.analytics.entity.DashboardWidget;
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
public class DashboardWidgetDTO {
    private Long id;
    private Long userId;
    @NotNull
    private DashboardWidget.WidgetType widgetType;
    @NotNull
    @Size(min = 1, max = 200)
    private String title;
    private String dataSource;
    private String config;
    private int position;
    private boolean isActive;
    private LocalDateTime createdAt;
}
