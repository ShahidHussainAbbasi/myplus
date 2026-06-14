package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.AlertsDTO;
import com.myplus.education.entity.Alerts;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.AlertsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertsService {

    private final AlertsRepository alertsRepository;

    public Page<AlertsDTO> getByUser(Long userId, Pageable pageable) {
        return alertsRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public AlertsDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public AlertsDTO create(AlertsDTO dto) {
        Alerts e = Alerts.builder()
                .userId(dto.getUserId())
                .c(dto.getC())
                .ut(dto.getUt())
                .at(dto.getAt())
                .deliveryType(dto.getDeliveryType())
                .dc(dto.getDc())
                .dp(dto.getDp())
                .ah(dto.getAh())
                .am(dto.getAm())
                .alertSignature(dto.getAlertSignature())
                .sd(dto.getSd())
                .ed(dto.getEd())
                .st(dto.getSt())
                .build();
        return toDto(alertsRepository.save(e));
    }

    @Transactional
    public AlertsDTO update(Long id, AlertsDTO dto) {
        Alerts e = getEntity(id);
        e.setC(dto.getC());
        e.setUt(dto.getUt());
        e.setAt(dto.getAt());
        e.setDeliveryType(dto.getDeliveryType());
        e.setDc(dto.getDc());
        e.setDp(dto.getDp());
        e.setAh(dto.getAh());
        e.setAm(dto.getAm());
        e.setAlertSignature(dto.getAlertSignature());
        e.setSd(dto.getSd());
        e.setEd(dto.getEd());
        e.setSt(dto.getSt());
        return toDto(alertsRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        alertsRepository.delete(getEntity(id));
    }

    public Alerts getEntity(Long id) {
        return alertsRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found: " + id));
    }

    public AlertsDTO toDto(Alerts e) {
        return AlertsDTO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .c(e.getC())
                .ut(e.getUt())
                .at(e.getAt())
                .deliveryType(e.getDeliveryType())
                .dc(e.getDc())
                .dp(e.getDp())
                .ah(e.getAh())
                .am(e.getAm())
                .alertSignature(e.getAlertSignature())
                .sd(e.getSd())
                .ed(e.getEd())
                .st(e.getSt())
                .build();
    }
}

