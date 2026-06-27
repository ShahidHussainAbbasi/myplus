package com.myplus.inventory.service;

import com.myplus.inventory.dto.SupplierDTO;
import com.myplus.inventory.entity.Supplier;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.inventory.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public List<SupplierDTO> getAll() {
        return supplierRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toDto).toList();
    }

    public SupplierDTO getById(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public SupplierDTO create(SupplierDTO dto) {
        Supplier s = fromDto(dto, new Supplier());
        s.setOrganizationId(CurrentUser.organizationId());
        s.setUserId(CurrentUser.userId());
        return toDto(supplierRepository.save(s));
    }

    @Transactional
    public SupplierDTO update(Long id, SupplierDTO dto) {
        Supplier s = getEntity(id);
        fromDto(dto, s);
        return toDto(supplierRepository.save(s));
    }

    @Transactional
    public void delete(Long id) {
        supplierRepository.delete(getEntity(id));
    }

    /** Scoped lookup — anti-IDOR. */
    public Supplier getEntity(Long id) {
        return supplierRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
    }

    private SupplierDTO toDto(Supplier s) {
        return SupplierDTO.builder()
                .id(s.getId())
                .name(s.getName())
                .contactPerson(s.getContactPerson())
                .email(s.getEmail())
                .phone(s.getPhone())
                .address(s.getAddress())
                .taxId(s.getTaxId())
                .paymentTerms(s.getPaymentTerms())
                .isActive(s.getIsActive())
                .build();
    }

    private Supplier fromDto(SupplierDTO dto, Supplier s) {
        s.setName(dto.getName());
        s.setContactPerson(dto.getContactPerson());
        s.setEmail(dto.getEmail());
        s.setPhone(dto.getPhone());
        s.setAddress(dto.getAddress());
        s.setTaxId(dto.getTaxId());
        s.setPaymentTerms(dto.getPaymentTerms());
        if (dto.getIsActive() != null) s.setIsActive(dto.getIsActive());
        return s;
    }
}
