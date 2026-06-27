package com.myplus.inventory.service;

import com.myplus.inventory.dto.WarehouseDTO;
import com.myplus.inventory.entity.StockEntry;
import com.myplus.inventory.entity.Warehouse;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final StockEntryRepository stockEntryRepository;

    public List<WarehouseDTO> getAll() {
        return warehouseRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toDto).toList();
    }

    public WarehouseDTO getById(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public WarehouseDTO create(WarehouseDTO dto) {
        Warehouse w = Warehouse.builder()
                .name(dto.getName())
                .location(dto.getLocation())
                .address(dto.getAddress())
                .capacity(dto.getCapacity())
                .managerId(dto.getManagerId())
                .organizationId(CurrentUser.organizationId())
                .userId(CurrentUser.userId())
                .build();
        return toDto(warehouseRepository.save(w));
    }

    @Transactional
    public WarehouseDTO update(Long id, WarehouseDTO dto) {
        Warehouse w = getEntity(id);
        w.setName(dto.getName());
        w.setLocation(dto.getLocation());
        w.setAddress(dto.getAddress());
        w.setCapacity(dto.getCapacity());
        w.setManagerId(dto.getManagerId());
        return toDto(warehouseRepository.save(w));
    }

    @Transactional
    public void delete(Long id) {
        warehouseRepository.delete(getEntity(id));
    }

    public Page<StockEntry> getStock(Long id, Pageable pageable) {
        getEntity(id);   // scoped — ensures the warehouse belongs to the caller's tenant
        return stockEntryRepository.findByWarehouseScoped(id, CurrentUser.organizationId(), CurrentUser.userId(), pageable);
    }

    /** Scoped lookup — anti-IDOR. */
    public Warehouse getEntity(Long id) {
        return warehouseRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + id));
    }

    private WarehouseDTO toDto(Warehouse w) {
        return WarehouseDTO.builder()
                .id(w.getId())
                .name(w.getName())
                .location(w.getLocation())
                .address(w.getAddress())
                .capacity(w.getCapacity())
                .managerId(w.getManagerId())
                .build();
    }
}
