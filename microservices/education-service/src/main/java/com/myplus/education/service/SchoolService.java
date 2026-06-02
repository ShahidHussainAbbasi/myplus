package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.SchoolDTO;
import com.myplus.education.entity.School;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.SchoolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SchoolService {

    private final SchoolRepository schoolRepository;

    public Page<SchoolDTO> getByUser(Long userId, Pageable pageable) {
        return schoolRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public SchoolDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public SchoolDTO create(SchoolDTO dto) {
        School e = School.builder()
                .name(dto.getName())
                .userId(dto.getUserId())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .address(dto.getAddress())
                .branchName(dto.getBranchName())
                .status(dto.getStatus())
                .build();
        return toDto(schoolRepository.save(e));
    }

    @Transactional
    public SchoolDTO update(Long id, SchoolDTO dto) {
        School e = getEntity(id);
        e.setName(dto.getName());
        e.setEmail(dto.getEmail());
        e.setPhone(dto.getPhone());
        e.setAddress(dto.getAddress());
        e.setBranchName(dto.getBranchName());
        e.setStatus(dto.getStatus());
        return toDto(schoolRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        schoolRepository.delete(getEntity(id));
    }

    public School getEntity(Long id) {
        return schoolRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("School not found: " + id));
    }

    public SchoolDTO toDto(School e) {
        return SchoolDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .userId(e.getUserId())
                .email(e.getEmail())
                .phone(e.getPhone())
                .address(e.getAddress())
                .branchName(e.getBranchName())
                .status(e.getStatus())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
