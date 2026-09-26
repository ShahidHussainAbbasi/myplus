package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.AlertsDTO;
import com.myplus.education.entity.Alerts;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.common.security.CurrentUser;
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

    /*
     * ANTI-IDOR -- resolve by id WITHIN THE CALLER'S TENANT, never by id alone.
     *
     * This read findById(id). The list endpoint scopes by user, but every by-id path -- get, update and
     * delete -- resolved a client-supplied id straight out of the table, so any signed-in education user
     * could read, rewrite or delete another school's row by guessing a number. The same shape as the
     * marketplace quote defect (SCOPE-1): the list is scoped, open-by-id is not.
     *
     * The tenancy data was never missing. The entity carries organizationId and userId, this repository
     * ALREADY had findByIdScoped, and the rest of the module uses it in twenty places. Only this newer
     * REST-style controller/service pair skipped it.
     */
    public Alerts getEntity(Long id) {
        return alertsRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
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

