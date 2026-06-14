package com.myplus.campaign.repository;

import com.myplus.campaign.entity.CampaignLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CampaignLogRepository extends JpaRepository<CampaignLog, Long> {
    Page<CampaignLog> findByCampaignId(Long campaignId, Pageable pageable);
    long countByCampaignIdAndStatus(Long campaignId, CampaignLog.Status status);
}
