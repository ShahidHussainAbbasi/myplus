package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.StudentDTO;
import com.myplus.education.entity.Student;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;

    public Page<StudentDTO> getByUser(Long userId, Pageable pageable) {
        return studentRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public StudentDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public StudentDTO create(StudentDTO dto) {
        Student e = Student.builder()
                .name(dto.getName())
                .userId(dto.getUserId())
                .ys(dto.getYs())
                .ye(dto.getYe())
                .enrollNo(dto.getEnrollNo())
                .enrollDate(dto.getEnrollDate())
                .feeMode(dto.getFeeMode())
                .email(dto.getEmail())
                .mobile(dto.getMobile())
                .address(dto.getAddress())
                .dateOfBirth(dto.getDateOfBirth())
                .gender(dto.getGender())
                .bloodGroup(dto.getBloodGroup())
                .status(dto.getStatus())
                .schoolId(dto.getSchoolId())
                .guardianId(dto.getGuardianId())
                .gradeId(dto.getGradeId())
                .vehicleId(dto.getVehicleId())
                .discountId(dto.getDiscountId())
                .nd(dto.getNd())
                .di(dto.getDi())
                .fee(dto.getFee())
                .dueDay(dto.getDueDay())
                .vf(dto.getVf())
                .pob(dto.getPob())
                .mn(dto.getMn())
                .wa(dto.getWa())
                .religion(dto.getReligion())
                .build();
        return toDto(studentRepository.save(e));
    }

    @Transactional
    public StudentDTO update(Long id, StudentDTO dto) {
        Student e = getEntity(id);
        e.setName(dto.getName());
        e.setYs(dto.getYs());
        e.setYe(dto.getYe());
        e.setEnrollNo(dto.getEnrollNo());
        e.setEnrollDate(dto.getEnrollDate());
        e.setFeeMode(dto.getFeeMode());
        e.setEmail(dto.getEmail());
        e.setMobile(dto.getMobile());
        e.setAddress(dto.getAddress());
        e.setDateOfBirth(dto.getDateOfBirth());
        e.setGender(dto.getGender());
        e.setBloodGroup(dto.getBloodGroup());
        e.setStatus(dto.getStatus());
        e.setSchoolId(dto.getSchoolId());
        e.setGuardianId(dto.getGuardianId());
        e.setGradeId(dto.getGradeId());
        e.setVehicleId(dto.getVehicleId());
        e.setDiscountId(dto.getDiscountId());
        e.setNd(dto.getNd());
        e.setDi(dto.getDi());
        e.setFee(dto.getFee());
        e.setDueDay(dto.getDueDay());
        e.setVf(dto.getVf());
        e.setPob(dto.getPob());
        e.setMn(dto.getMn());
        e.setWa(dto.getWa());
        e.setReligion(dto.getReligion());
        return toDto(studentRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        studentRepository.delete(getEntity(id));
    }

    public Student getEntity(Long id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found: " + id));
    }

    public StudentDTO toDto(Student e) {
        return StudentDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .userId(e.getUserId())
                .ys(e.getYs())
                .ye(e.getYe())
                .enrollNo(e.getEnrollNo())
                .enrollDate(e.getEnrollDate())
                .feeMode(e.getFeeMode())
                .email(e.getEmail())
                .mobile(e.getMobile())
                .address(e.getAddress())
                .dateOfBirth(e.getDateOfBirth())
                .gender(e.getGender())
                .bloodGroup(e.getBloodGroup())
                .status(e.getStatus())
                .schoolId(e.getSchoolId())
                .guardianId(e.getGuardianId())
                .gradeId(e.getGradeId())
                .vehicleId(e.getVehicleId())
                .discountId(e.getDiscountId())
                .nd(e.getNd())
                .di(e.getDi())
                .fee(e.getFee())
                .dueDay(e.getDueDay())
                .vf(e.getVf())
                .pob(e.getPob())
                .mn(e.getMn())
                .wa(e.getWa())
                .religion(e.getReligion())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
