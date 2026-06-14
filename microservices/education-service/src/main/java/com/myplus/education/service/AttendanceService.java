package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.AttendanceDTO;
import com.myplus.education.entity.Attendance;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.AttendanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;

    public Page<AttendanceDTO> getByUser(Long userId, Pageable pageable) {
        return attendanceRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public AttendanceDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public AttendanceDTO create(AttendanceDTO dto) {
        Attendance e = Attendance.builder()
                .userId(dto.getUserId())
                .en(dto.getEn())
                .sn(dto.getSn())
                .grid(dto.getGrid())
                .gn(dto.getGn())
                .in(dto.getIn())
                .out(dto.getOut())
                .status(dto.getStatus())
                .dt(dto.getDt())
                .rem(dto.getRem())
                .build();
        return toDto(attendanceRepository.save(e));
    }

    @Transactional
    public AttendanceDTO update(Long id, AttendanceDTO dto) {
        Attendance e = getEntity(id);
        e.setEn(dto.getEn());
        e.setSn(dto.getSn());
        e.setGrid(dto.getGrid());
        e.setGn(dto.getGn());
        e.setIn(dto.getIn());
        e.setOut(dto.getOut());
        e.setStatus(dto.getStatus());
        e.setDt(dto.getDt());
        e.setRem(dto.getRem());
        return toDto(attendanceRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        attendanceRepository.delete(getEntity(id));
    }

    public Attendance getEntity(Long id) {
        return attendanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance not found: " + id));
    }

    public AttendanceDTO toDto(Attendance e) {
        return AttendanceDTO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .en(e.getEn())
                .sn(e.getSn())
                .grid(e.getGrid())
                .gn(e.getGn())
                .in(e.getIn())
                .out(e.getOut())
                .status(e.getStatus())
                .dt(e.getDt())
                .rem(e.getRem())
                .build();
    }
}
