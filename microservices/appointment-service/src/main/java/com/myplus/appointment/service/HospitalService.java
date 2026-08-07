package com.myplus.appointment.service;

import com.myplus.appointment.dto.HospitalDTO;
import com.myplus.appointment.entity.Venue;
import com.myplus.appointment.exception.ResourceNotFoundException;
import com.myplus.appointment.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class HospitalService {

    private final VenueRepository repo;
    private final ModelMapper mapper;

    @Transactional
    public HospitalDTO create(HospitalDTO dto, Long orgId) {
        Venue h = mapper.map(dto, Venue.class);
        h.setId(null);
        h.setOrganizationId(orgId);
        return mapper.map(repo.save(h), HospitalDTO.class);
    }

    public List<HospitalDTO> list(Long orgId) {
        return repo.findByOrganizationId(orgId).stream().map(h -> mapper.map(h, HospitalDTO.class)).toList();
    }

    public HospitalDTO get(Long id, Long orgId) {
        return repo.findByIdAndOrganizationId(id, orgId).map(h -> mapper.map(h, HospitalDTO.class))
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + id));
    }

    @Transactional
    public void delete(Long id, Long orgId) {
        Venue h = repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + id));
        repo.delete(h);
    }
}
