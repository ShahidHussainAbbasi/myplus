package com.myplus.appointment.service;

import com.myplus.appointment.dto.DoctorDTO;
import com.myplus.appointment.entity.Doctor;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository repo;
    private final ModelMapper mapper;

    @Transactional
    public DoctorDTO create(DoctorDTO dto, Long orgId) {
        Doctor d = mapper.map(dto, Doctor.class);
        d.setId(null);
        d.setOrganizationId(orgId);
        return mapper.map(repo.save(d), DoctorDTO.class);
    }

    public List<DoctorDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(d -> mapper.map(d, DoctorDTO.class)).toList();
    }

    public List<DoctorDTO> listByHospital(Long hospitalId, Long orgId) {
        return repo.findByHospitalIdAndOrganizationId(hospitalId, orgId).stream()
                .map(d -> mapper.map(d, DoctorDTO.class)).toList();
    }

    public DoctorDTO get(Long id, Long orgId) {
        return repo.findByIdAndOrganizationId(id, orgId).map(d -> mapper.map(d, DoctorDTO.class))
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + id));
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Doctor d = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found: " + id));
        repo.delete(d);
    }
}
