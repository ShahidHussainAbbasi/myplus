package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.GradeDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.GradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GradeService {

    private final GradeRepository gradeRepository;

    public Page<GradeDTO> getByUser(Long userId, Pageable pageable) {
        return gradeRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public GradeDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public GradeDTO create(GradeDTO dto) {
        Grade e = Grade.builder()
                .name(dto.getName())
                .userId(dto.getUserId())
                .code(dto.getCode())
                .section(dto.getSection())
                .timeFrom(dto.getTimeFrom())
                .timeTo(dto.getTimeTo())
                .status(dto.getStatus())
                .schoolId(dto.getSchoolId())
                .fee(dto.getFee())
                .room(dto.getRoom())
                .build();
        return toDto(gradeRepository.save(e));
    }

    @Transactional
    public GradeDTO update(Long id, GradeDTO dto) {
        Grade e = getEntity(id);
        e.setName(dto.getName());
        e.setCode(dto.getCode());
        e.setSection(dto.getSection());
        e.setTimeFrom(dto.getTimeFrom());
        e.setTimeTo(dto.getTimeTo());
        e.setStatus(dto.getStatus());
        e.setSchoolId(dto.getSchoolId());
        e.setFee(dto.getFee());
        e.setRoom(dto.getRoom());
        return toDto(gradeRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        gradeRepository.delete(getEntity(id));
    }

    public Grade getEntity(Long id) {
        return gradeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Grade not found: " + id));
    }

    public GradeDTO toDto(Grade e) {
        return GradeDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .userId(e.getUserId())
                .code(e.getCode())
                .section(e.getSection())
                .timeFrom(e.getTimeFrom())
                .timeTo(e.getTimeTo())
                .status(e.getStatus())
                .schoolId(e.getSchoolId())
                .fee(e.getFee())
                .room(e.getRoom())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
