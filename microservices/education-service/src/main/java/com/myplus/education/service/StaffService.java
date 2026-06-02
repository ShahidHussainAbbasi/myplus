package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.StaffDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Staff;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StaffService {

    private final StaffRepository staffRepository;
    private final GradeRepository gradeRepository;

    public Page<StaffDTO> getByUser(Long userId, Pageable pageable) {
        return staffRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public StaffDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public StaffDTO create(StaffDTO dto) {
        Staff e = Staff.builder()
                .name(dto.getName())
                .userId(dto.getUserId())
                .email(dto.getEmail())
                .mobile(dto.getMobile())
                .phone(dto.getPhone())
                .address(dto.getAddress())
                .designation(dto.getDesignation())
                .staffDOB(dto.getStaffDOB())
                .gender(dto.getGender())
                .timeIn(dto.getTimeIn())
                .timeOut(dto.getTimeOut())
                .qualification(dto.getQualification())
                .martialStatus(dto.getMartialStatus())
                .status(dto.getStatus())
                .grades(resolveGrades(dto.getGradeIds()))
                .build();
        return toDto(staffRepository.save(e));
    }

    @Transactional
    public StaffDTO update(Long id, StaffDTO dto) {
        Staff e = getEntity(id);
        e.setName(dto.getName());
        e.setEmail(dto.getEmail());
        e.setMobile(dto.getMobile());
        e.setPhone(dto.getPhone());
        e.setAddress(dto.getAddress());
        e.setDesignation(dto.getDesignation());
        e.setStaffDOB(dto.getStaffDOB());
        e.setGender(dto.getGender());
        e.setTimeIn(dto.getTimeIn());
        e.setTimeOut(dto.getTimeOut());
        e.setQualification(dto.getQualification());
        e.setMartialStatus(dto.getMartialStatus());
        e.setStatus(dto.getStatus());
        e.setGrades(resolveGrades(dto.getGradeIds()));
        return toDto(staffRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        staffRepository.delete(getEntity(id));
    }

    public Staff getEntity(Long id) {
        return staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found: " + id));
    }

    private List<Grade> resolveGrades(List<Long> gradeIds) {
        if (gradeIds == null || gradeIds.isEmpty()) return Collections.emptyList();
        return gradeIds.stream()
                .map(gid -> gradeRepository.findById(gid).orElse(null))
                .filter(g -> g != null)
                .collect(Collectors.toList());
    }

    public StaffDTO toDto(Staff e) {
        List<Long> gradeIds = e.getGrades() != null
                ? e.getGrades().stream().map(Grade::getId).collect(Collectors.toList())
                : Collections.emptyList();
        return StaffDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .userId(e.getUserId())
                .email(e.getEmail())
                .mobile(e.getMobile())
                .phone(e.getPhone())
                .address(e.getAddress())
                .designation(e.getDesignation())
                .staffDOB(e.getStaffDOB())
                .gender(e.getGender())
                .timeIn(e.getTimeIn())
                .timeOut(e.getTimeOut())
                .qualification(e.getQualification())
                .martialStatus(e.getMartialStatus())
                .status(e.getStatus())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .gradeIds(gradeIds)
                .build();
    }
}
