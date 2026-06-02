package com.myplus.campaign.dto;

import com.myplus.campaign.entity.Campaign;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignDTO {
    private Long id;

    @NotNull
    @Size(min = 1, max = 200)
    private String name;

    private String description;

    @NotNull
    private Campaign.Type type;

    private Campaign.Status status;
    private String subject;
    private String content;
    private Long templateId;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long createdBy;
    private int totalRecipients;
    private int sent;
    private int delivered;
    private int opened;
    private int clicked;
    private int bounced;
    private int unsubscribed;
    private BigDecimal budget;
    private BigDecimal spent;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
