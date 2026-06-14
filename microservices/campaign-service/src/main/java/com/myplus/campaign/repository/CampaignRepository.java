package com.myplus.campaign.repository;

import com.myplus.campaign.entity.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    Page<Campaign> findByStatus(Campaign.Status status, Pageable pageable);
    Page<Campaign> findByCreatedBy(Long createdBy, Pageable pageable);
    List<Campaign> findByScheduledAtBetween(LocalDateTime start, LocalDateTime end);
}
