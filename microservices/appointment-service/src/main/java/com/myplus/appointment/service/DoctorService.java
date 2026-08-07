package com.myplus.appointment.service;

import com.myplus.appointment.dto.DoctorDTO;
import com.myplus.appointment.entity.Provider;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final ProviderRepository repo;
    private final ModelMapper mapper;

    @Transactional
    public DoctorDTO create(DoctorDTO dto, Long orgId) {
        Provider d = mapper.map(dto, Provider.class);
        d.setId(null);
        d.setOrganizationId(orgId);
        return mapper.map(repo.save(d), DoctorDTO.class);
    }

    public List<DoctorDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(d -> mapper.map(d, DoctorDTO.class)).toList();
    }

    public List<DoctorDTO> listByHospital(Long hospitalId, Long orgId) {
        return repo.findByVenueIdAndOrganizationId(hospitalId, orgId).stream()
                .map(d -> mapper.map(d, DoctorDTO.class)).toList();
    }

    public DoctorDTO get(Long id, Long orgId) {
        return repo.findByIdAndOrganizationId(id, orgId).map(d -> mapper.map(d, DoctorDTO.class))
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + id));
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Provider d = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + id));
        repo.delete(d);
    }
}
