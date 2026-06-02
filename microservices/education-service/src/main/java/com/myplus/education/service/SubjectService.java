package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.SubjectDTO;
import com.myplus.education.entity.Grade;
import com.myplus.education.entity.Subject;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.GradeRepository;
import com.myplus.education.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final GradeRepository gradeRepository;

    public Page<SubjectDTO> getByUser(Long userId, Pageable pageable) {
        return subjectRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public SubjectDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public SubjectDTO create(SubjectDTO dto) {
        Grade grade = dto.getGradeId() != null
                ? gradeRepository.findById(dto.getGradeId()).orElse(null) : null;
        Subject e = Subject.builder()
                .name(dto.getName())
                .userId(dto.getUserId())
                .code(dto.getCode())
                .publisher(dto.getPublisher())
                .edition(dto.getEdition())
                .status(dto.getStatus())
                .grade(grade)
                .build();
        return toDto(subjectRepository.save(e));
    }

    @Transactional
    public SubjectDTO update(Long id, SubjectDTO dto) {
        Subject e = getEntity(id);
        e.setName(dto.getName());
        e.setCode(dto.getCode());
        e.setPublisher(dto.getPublisher());
        e.setEdition(dto.getEdition());
        e.setStatus(dto.getStatus());
        if (dto.getGradeId() != null) {
            e.setGrade(gradeRepository.findById(dto.getGradeId()).orElse(null));
        }
        return toDto(subjectRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        subjectRepository.delete(getEntity(id));
    }

    public Subject getEntity(Long id) {
        return subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found: " + id));
    }

    public SubjectDTO toDto(Subject e) {
        return SubjectDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .userId(e.getUserId())
                .code(e.getCode())
                .publisher(e.getPublisher())
                .edition(e.getEdition())
                .status(e.getStatus())
                .gradeId(e.getGrade() != null ? e.getGrade().getId() : null)
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
