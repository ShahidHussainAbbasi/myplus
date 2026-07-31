package com.myplus.education.service;

import com.myplus.education.dto.EducationDTOs.FeeCollectionDTO;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.exception.ResourceNotFoundException;
import com.myplus.education.repository.FeeCollectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeeCollectionService {

    private final FeeCollectionRepository feeCollectionRepository;

    public Page<FeeCollectionDTO> getByUser(Long userId, Pageable pageable) {
        return feeCollectionRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    public FeeCollectionDTO get(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public FeeCollectionDTO create(FeeCollectionDTO dto) {
        FeeCollection e = FeeCollection.builder()
                .userId(dto.getUserId())
                .enrollNo(dto.getEnrollNo())
                .discountType(dto.getDiscountType())
                .discount(dto.getDiscount())
                .dueDayOfMonth(dto.getDueDayOfMonth())
                .dueAmount(dto.getDueAmount())
                .fee(dto.getFee())
                .feePaid(dto.getFeePaid())
                .paymentDate(dto.getPaymentDate())
                .otherDues(dto.getOtherDues())
                .otherDuesDescription(dto.getOtherDuesDescription())
                .payee(dto.getPayee())
                .receivedBy(dto.getReceivedBy())
                .receivedIn(dto.getReceivedIn())
                .checkNo(dto.getCheckNo())
                .vehicleFee(dto.getVehicleFee())
                .dueBalance(dto.getDueBalance())
                .build();
        return toDto(feeCollectionRepository.save(e));
    }

    @Transactional
    public FeeCollectionDTO update(Long id, FeeCollectionDTO dto) {
        FeeCollection e = getEntity(id);
        e.setEnrollNo(dto.getEnrollNo());
        e.setDiscountType(dto.getDiscountType());
        e.setDiscount(dto.getDiscount());
        e.setDueDayOfMonth(dto.getDueDayOfMonth());
        e.setDueAmount(dto.getDueAmount());
        e.setFee(dto.getFee());
        e.setFeePaid(dto.getFeePaid());
        e.setPaymentDate(dto.getPaymentDate());
        e.setOtherDues(dto.getOtherDues());
        e.setOtherDuesDescription(dto.getOtherDuesDescription());
        e.setPayee(dto.getPayee());
        e.setReceivedBy(dto.getReceivedBy());
        e.setReceivedIn(dto.getReceivedIn());
        e.setCheckNo(dto.getCheckNo());
        e.setVehicleFee(dto.getVehicleFee());
        e.setDueBalance(dto.getDueBalance());
        return toDto(feeCollectionRepository.save(e));
    }

    @Transactional
    public void delete(Long id) {
        feeCollectionRepository.delete(getEntity(id));
    }

    public FeeCollection getEntity(Long id) {
        return feeCollectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeeCollection not found: " + id));
    }

    public FeeCollectionDTO toDto(FeeCollection e) {
        return FeeCollectionDTO.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .enrollNo(e.getEnrollNo())
                .discountType(e.getDiscountType())
                .discount(e.getDiscount())
                .dueDayOfMonth(e.getDueDayOfMonth())
                .dueAmount(e.getDueAmount())
                .fee(e.getFee())
                .feePaid(e.getFeePaid())
                .paymentDate(e.getPaymentDate())
                .otherDues(e.getOtherDues())
                .otherDuesDescription(e.getOtherDuesDescription())
                .payee(e.getPayee())
                .receivedBy(e.getReceivedBy())
                .receivedIn(e.getReceivedIn())
                .checkNo(e.getCheckNo())
                .vehicleFee(e.getVehicleFee())
                .dueBalance(e.getDueBalance())
                .build();
    }
}
