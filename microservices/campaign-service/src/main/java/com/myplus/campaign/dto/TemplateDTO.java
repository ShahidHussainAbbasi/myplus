package com.myplus.campaign.dto;

import com.myplus.campaign.entity.Template;
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
public class TemplateDTO {
    private Long id;

    @NotNull
    @Size(min = 1, max = 200)
    private String name;

    @NotNull
    private Template.Type type;

    private String subject;
    private String htmlContent;
    private String textContent;
    private Long createdBy;
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
