package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.OwnerDTO;
import com.myplus.education.entity.Owner;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerService {

    private final OwnerRepository ownerRepository;

    public Page<OwnerDTO> getByUser(Long userId, Pageable pageable) {
        return ownerRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public OwnerDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public OwnerDTO create(OwnerDTO dto) {
        Owner e = Owner.builder()
                .userId(dto.getUserId())
                .name(dto.getName())
                .email(dto.getEmail())
                .mobile(dto.getMobile())
                .address(dto.getAddress())
                .status(dto.getStatus())
                .build();
        return toDto(ownerRepository.save(e));
    }

    @Transactional
    public OwnerDTO update(Long id, OwnerDTO dto) {
        Owner e = getEntity(id);
        e.setName(dto.getName());
        e.setEmail(dto.getEmail());
        e.setMobile(dto.getMobile());
        e.setAddress(dto.getAddress());
        e.setStatus(dto.getStatus());
        return toDto(ownerRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        ownerRepository.delete(getEntity(id));
    }

    public Owner getEntity(Long id) {
        return ownerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found: " + id));
    }

    public OwnerDTO toDto(Owner e) {
        return OwnerDTO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .name(e.getName())
                .email(e.getEmail())
                .mobile(e.getMobile())
                .address(e.getAddress())
                .status(e.getStatus())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
