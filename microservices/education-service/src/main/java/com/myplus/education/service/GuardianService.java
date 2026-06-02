package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.GuardianDTO;
import com.myplus.education.entity.Guardian;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.GuardianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuardianService {

    private final GuardianRepository guardianRepository;

    public Page<GuardianDTO> getByUser(Long userId, Pageable pageable) {
        return guardianRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public GuardianDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public GuardianDTO create(GuardianDTO dto) {
        Guardian e = Guardian.builder()
                .name(dto.getName())
                .userId(dto.getUserId())
                .email(dto.getEmail())
                .mobile(dto.getMobile())
                .phone(dto.getPhone())
                .tempAddress(dto.getTempAddress())
                .permAddress(dto.getPermAddress())
                .gender(dto.getGender())
                .relation(dto.getRelation())
                .occupation(dto.getOccupation())
                .status(dto.getStatus())
                .cnic(dto.getCnic())
                .build();
        return toDto(guardianRepository.save(e));
    }

    @Transactional
    public GuardianDTO update(Long id, GuardianDTO dto) {
        Guardian e = getEntity(id);
        e.setName(dto.getName());
        e.setEmail(dto.getEmail());
        e.setMobile(dto.getMobile());
        e.setPhone(dto.getPhone());
        e.setTempAddress(dto.getTempAddress());
        e.setPermAddress(dto.getPermAddress());
        e.setGender(dto.getGender());
        e.setRelation(dto.getRelation());
        e.setOccupation(dto.getOccupation());
        e.setStatus(dto.getStatus());
        e.setCnic(dto.getCnic());
        return toDto(guardianRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        guardianRepository.delete(getEntity(id));
    }

    public Guardian getEntity(Long id) {
        return guardianRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Guardian not found: " + id));
    }

    public GuardianDTO toDto(Guardian e) {
        return GuardianDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .userId(e.getUserId())
                .email(e.getEmail())
                .mobile(e.getMobile())
                .phone(e.getPhone())
                .tempAddress(e.getTempAddress())
                .permAddress(e.getPermAddress())
                .gender(e.getGender())
                .relation(e.getRelation())
                .occupation(e.getOccupation())
                .status(e.getStatus())
                .cnic(e.getCnic())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
