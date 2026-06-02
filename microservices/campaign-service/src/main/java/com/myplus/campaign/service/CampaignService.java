package com.myplus.campaign.service;

import com.myplus.campaign.dto.CampaignDTO;
import com.myplus.campaign.dto.CampaignStatsDTO;
import com.myplus.campaign.entity.Campaign;
import com.myplus.campaign.exception.ResourceNotFoundException;
import com.myplus.campaign.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final ModelMapper modelMapper = new ModelMapper();

    public CampaignDTO createCampaign(CampaignDTO dto, Long userId) {
        Campaign c = modelMapper.map(dto, Campaign.class);
        c.setId(null);
        c.setCreatedBy(userId);
        if (c.getStatus() == null) c.setStatus(Campaign.Status.DRAFT);
        return toDto(campaignRepository.save(c));
    }

    public CampaignDTO updateCampaign(Long id, CampaignDTO dto) {
        Campaign existing = findOrThrow(id);
        existing.setName(dto.getName());
        existing.setDescription(dto.getDescription());
        existing.setType(dto.getType());
        existing.setSubject(dto.getSubject());
        existing.setContent(dto.getContent());
        existing.setTemplateId(dto.getTemplateId());
        existing.setScheduledAt(dto.getScheduledAt());
        existing.setBudget(dto.getBudget());
        return toDto(campaignRepository.save(existing));
    }

    @Transactional(readOnly = true)
    public CampaignDTO getCampaignById(Long id) {
        return toDto(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<CampaignDTO> getAllCampaigns(Long userId, Pageable pageable) {
        Page<Campaign> page = userId != null
                ? campaignRepository.findByCreatedBy(userId, pageable)
                : campaignRepository.findAll(pageable);
        return page.map(this::toDto);
    }

    public void deleteCampaign(Long id) {
        Campaign c = findOrThrow(id);
        campaignRepository.delete(c);
    }

    public CampaignDTO launchCampaign(Long id) {
        Campaign c = findOrThrow(id);
        c.setStatus(Campaign.Status.RUNNING);
        c.setStartedAt(LocalDateTime.now());
        return toDto(campaignRepository.save(c));
    }

    public CampaignDTO pauseCampaign(Long id) {
        Campaign c = findOrThrow(id);
        c.setStatus(Campaign.Status.PAUSED);
        return toDto(campaignRepository.save(c));
    }

    public CampaignDTO cancelCampaign(Long id) {
        Campaign c = findOrThrow(id);
        c.setStatus(Campaign.Status.CANCELLED);
        return toDto(campaignRepository.save(c));
    }

    public CampaignDTO scheduleCampaign(Long id, LocalDateTime scheduledAt) {
        Campaign c = findOrThrow(id);
        c.setStatus(Campaign.Status.SCHEDULED);
        c.setScheduledAt(scheduledAt);
        return toDto(campaignRepository.save(c));
    }

    @Transactional(readOnly = true)
    public CampaignStatsDTO getCampaignStats(Long id) {
        Campaign c = findOrThrow(id);
        double deliveryRate = c.getSent() > 0 ? (c.getDelivered() * 100.0) / c.getSent() : 0.0;
        double openRate = c.getDelivered() > 0 ? (c.getOpened() * 100.0) / c.getDelivered() : 0.0;
        double clickRate = c.getOpened() > 0 ? (c.getClicked() * 100.0) / c.getOpened() : 0.0;
        return CampaignStatsDTO.builder()
                .campaignId(c.getId())
                .name(c.getName())
                .totalRecipients(c.getTotalRecipients())
                .sent(c.getSent())
                .delivered(c.getDelivered())
                .opened(c.getOpened())
                .clicked(c.getClicked())
                .bounced(c.getBounced())
                .deliveryRate(deliveryRate)
                .openRate(openRate)
                .clickRate(clickRate)
                .build();
    }

    public CampaignDTO cloneCampaign(Long id) {
        Campaign orig = findOrThrow(id);
        Campaign copy = Campaign.builder()
                .name("Copy of " + orig.getName())
                .description(orig.getDescription())
                .type(orig.getType())
                .status(Campaign.Status.DRAFT)
                .subject(orig.getSubject())
                .content(orig.getContent())
                .templateId(orig.getTemplateId())
                .createdBy(orig.getCreatedBy())
                .budget(orig.getBudget())
                .build();
        return toDto(campaignRepository.save(copy));
    }

    private Campaign findOrThrow(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found: " + id));
    }

    private CampaignDTO toDto(Campaign c) {
        return modelMapper.map(c, CampaignDTO.class);
    }
}
