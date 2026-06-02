package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.AlertChannelDTO;
import com.myplus.education.entity.AlertChannel;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.AlertChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertChannelService {

    private final AlertChannelRepository alertChannelRepository;

    public Page<AlertChannelDTO> getByUser(Long userId, Pageable pageable) {
        return alertChannelRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public AlertChannelDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public AlertChannelDTO create(AlertChannelDTO dto) {
        AlertChannel e = AlertChannel.builder()
                .userId(dto.getUserId())
                .c(dto.getC())
                .cn(dto.getCn())
                .ut(dto.getUt())
                .dt(dto.getDt())
                .s(dto.getS())
                .build();
        return toDto(alertChannelRepository.save(e));
    }

    @Transactional
    public AlertChannelDTO update(Long id, AlertChannelDTO dto) {
        AlertChannel e = getEntity(id);
        e.setC(dto.getC());
        e.setCn(dto.getCn());
        e.setUt(dto.getUt());
        e.setDt(dto.getDt());
        e.setS(dto.getS());
        return toDto(alertChannelRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        alertChannelRepository.delete(getEntity(id));
    }

    public AlertChannel getEntity(Long id) {
        return alertChannelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AlertChannel not found: " + id));
    }

    public AlertChannelDTO toDto(AlertChannel e) {
        return AlertChannelDTO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .c(e.getC())
                .cn(e.getCn())
                .ut(e.getUt())
                .dt(e.getDt())
                .s(e.getS())
                .build();
    }
}

