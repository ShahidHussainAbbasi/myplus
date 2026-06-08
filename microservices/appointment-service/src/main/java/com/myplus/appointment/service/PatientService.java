package com.myplus.appointment.service;

import com.myplus.appointment.dto.PatientDTO;
import com.myplus.appointment.entity.Patient;
import com.myplus.appointment.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository repo;
    private final ModelMapper mapper;

    @Transactional
    public PatientDTO create(PatientDTO dto, Long orgId) {
        Patient p = mapper.map(dto, Patient.class);
        p.setId(null);
        p.setOrganizationId(orgId);
        return mapper.map(repo.save(p), PatientDTO.class);
    }

    public List<PatientDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(p -> mapper.map(p, PatientDTO.class)).toList();
    }
}
