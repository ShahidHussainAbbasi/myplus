package com.myplus.appointment.service;

import com.myplus.appointment.dto.HospitalDTO;
import com.myplus.appointment.entity.Hospital;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HospitalService {

    private final HospitalRepository repo;
    private final ModelMapper mapper;

    @Transactional
    public HospitalDTO create(HospitalDTO dto, Long orgId) {
        Hospital h = mapper.map(dto, Hospital.class);
        h.setId(null);
        h.setOrganizationId(orgId);
        return mapper.map(repo.save(h), HospitalDTO.class);
    }

    public List<HospitalDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(h -> mapper.map(h, HospitalDTO.class)).toList();
    }

    public HospitalDTO get(Long id, Long orgId) {
        return repo.findByIdAndOrganizationId(id, orgId).map(h -> mapper.map(h, HospitalDTO.class))
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + id));
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Hospital h = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + id));
        repo.delete(h);
    }
}
