package com.myplus.campaign.service;

import com.myplus.campaign.dto.CampaignStatsDTO;
import com.myplus.campaign.entity.Campaign;
import com.myplus.campaign.entity.CampaignLog;
import com.myplus.campaign.exception.ResourceNotFoundException;
import com.myplus.campaign.repository.CampaignLogRepository;
import com.myplus.campaign.repository.CampaignRepository;
import com.myplus.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CampaignLogService {

    private final CampaignLogRepository logRepository;
    private final CampaignRepository campaignRepository;

    public CampaignLog logEvent(CampaignLog log) {
        return logRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<CampaignLog> getByCampaign(Long campaignId, Pageable pageable) {
        requireOwnedCampaign(campaignId);   // logs are reachable only through a campaign the caller owns
        return logRepository.findByCampaignId(campaignId, pageable);
    }

    @Transactional(readOnly = true)
    public CampaignStatsDTO getStats(Long campaignId) {
        Campaign c = requireOwnedCampaign(campaignId);
        long sent = logRepository.countByCampaignIdAndStatus(campaignId, CampaignLog.Status.SENT);
        long delivered = logRepository.countByCampaignIdAndStatus(campaignId, CampaignLog.Status.DELIVERED);
        long opened = logRepository.countByCampaignIdAndStatus(campaignId, CampaignLog.Status.OPENED);
        long clicked = logRepository.countByCampaignIdAndStatus(campaignId, CampaignLog.Status.CLICKED);
        long bounced = logRepository.countByCampaignIdAndStatus(campaignId, CampaignLog.Status.BOUNCED);

        double deliveryRate = sent > 0 ? (delivered * 100.0) / sent : 0.0;
        double openRate = delivered > 0 ? (opened * 100.0) / delivered : 0.0;
        double clickRate = opened > 0 ? (clicked * 100.0) / opened : 0.0;

        return CampaignStatsDTO.builder()
                .campaignId(c.getId())
                .name(c.getName())
                .totalRecipients(c.getTotalRecipients())
                .sent((int) sent)
                .delivered((int) delivered)
                .opened((int) opened)
                .clicked((int) clicked)
                .bounced((int) bounced)
                .deliveryRate(deliveryRate)
                .openRate(openRate)
                .clickRate(clickRate)
                .build();
    }

    /** Anti-IDOR: the campaign must belong to the caller's tenant before its logs/stats are exposed. */
    private Campaign requireOwnedCampaign(Long campaignId) {
        return campaignRepository.findByIdScoped(campaignId, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found: " + campaignId));
    }
}
