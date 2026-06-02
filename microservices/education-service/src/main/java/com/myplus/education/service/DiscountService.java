package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.DiscountDTO;
import com.myplus.education.entity.Discount;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.DiscountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountRepository discountRepository;

    public Page<DiscountDTO> getByUser(Long userId, Pageable pageable) {
        return discountRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public DiscountDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public DiscountDTO create(DiscountDTO dto) {
        Discount e = Discount.builder()
                .userId(dto.getUserId())
                .name(dto.getName())
                .di(dto.getDi())
                .amount(dto.getAmount())
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .description(dto.getDescription())
                .referenceName(dto.getReferenceName())
                .referenceMobile(dto.getReferenceMobile())
                .status(dto.getStatus())
                .build();
        return toDto(discountRepository.save(e));
    }

    @Transactional
    public DiscountDTO update(Long id, DiscountDTO dto) {
        Discount e = getEntity(id);
        e.setName(dto.getName());
        e.setDi(dto.getDi());
        e.setAmount(dto.getAmount());
        e.setStartDate(dto.getStartDate());
        e.setEndDate(dto.getEndDate());
        e.setDescription(dto.getDescription());
        e.setReferenceName(dto.getReferenceName());
        e.setReferenceMobile(dto.getReferenceMobile());
        e.setStatus(dto.getStatus());
        return toDto(discountRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        discountRepository.delete(getEntity(id));
    }

    public Discount getEntity(Long id) {
        return discountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Discount not found: " + id));
    }

    public DiscountDTO toDto(Discount e) {
        return DiscountDTO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .name(e.getName())
                .di(e.getDi())
                .amount(e.getAmount())
                .startDate(e.getStartDate())
                .endDate(e.getEndDate())
                .description(e.getDescription())
                .referenceName(e.getReferenceName())
                .referenceMobile(e.getReferenceMobile())
                .status(e.getStatus())
                .build();
    }
}
