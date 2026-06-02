package com.myplus.campaign.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStatsDTO {
    private Long campaignId;
    private String name;
    private int totalRecipients;
    private int sent;
    private int delivered;
    private int opened;
    private int clicked;
    private int bounced;
    private double deliveryRate;
    private double openRate;
    private double clickRate;
}
