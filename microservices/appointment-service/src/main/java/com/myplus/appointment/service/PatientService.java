package com.myplus.appointment.service;

import com.myplus.appointment.dto.PatientDTO;
import com.myplus.appointment.entity.Attendee;
import com.myplus.appointment.repository.AttendeeRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final AttendeeRepository repo;
    private final ModelMapper mapper;

    @Transactional
    public PatientDTO create(PatientDTO dto, Long orgId) {
        Attendee p = mapper.map(dto, Attendee.class);
        p.setId(null);
        p.setOrganizationId(orgId);
        return mapper.map(repo.save(p), PatientDTO.class);
    }

    public List<PatientDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(p -> mapper.map(p, PatientDTO.class)).toList();
    }
}
