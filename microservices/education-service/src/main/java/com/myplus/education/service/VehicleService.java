package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.VehicleDTO;
import com.myplus.education.entity.Vehicle;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    public Page<VehicleDTO> getByUser(Long userId, Pageable pageable) {
        return vehicleRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public VehicleDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public VehicleDTO create(VehicleDTO dto) {
        Vehicle e = Vehicle.builder()
                .name(dto.getName())
                .number(dto.getNumber())
                .driverName(dto.getDriverName())
                .driverMobile(dto.getDriverMobile())
                .ownerName(dto.getOwnerName())
                .ownerMobile(dto.getOwnerMobile())
                .userId(dto.getUserId())
                .status(dto.getStatus())
                .schoolId(dto.getSchoolId())
                .build();
        return toDto(vehicleRepository.save(e));
    }

    @Transactional
    public VehicleDTO update(Long id, VehicleDTO dto) {
        Vehicle e = getEntity(id);
        e.setName(dto.getName());
        e.setNumber(dto.getNumber());
        e.setDriverName(dto.getDriverName());
        e.setDriverMobile(dto.getDriverMobile());
        e.setOwnerName(dto.getOwnerName());
        e.setOwnerMobile(dto.getOwnerMobile());
        e.setStatus(dto.getStatus());
        e.setSchoolId(dto.getSchoolId());
        return toDto(vehicleRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        vehicleRepository.delete(getEntity(id));
    }

    public Vehicle getEntity(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + id));
    }

    public VehicleDTO toDto(Vehicle e) {
        return VehicleDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .number(e.getNumber())
                .driverName(e.getDriverName())
                .driverMobile(e.getDriverMobile())
                .ownerName(e.getOwnerName())
                .ownerMobile(e.getOwnerMobile())
                .userId(e.getUserId())
                .status(e.getStatus())
                .schoolId(e.getSchoolId())
                .dated(e.getDated())
                .updated(e.getUpdated())
                .build();
    }
}
