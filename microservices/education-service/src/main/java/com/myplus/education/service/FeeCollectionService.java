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
                .en(dto.getEn())
                .dt(dto.getDt())
                .d(dto.getD())
                .dd(dto.getDd())
                .da(dto.getDa())
                .f(dto.getF())
                .fp(dto.getFp())
                .pd(dto.getPd())
                .od(dto.getOd())
                .odd(dto.getOdd())
                .p(dto.getP())
                .rb(dto.getRb())
                .ri(dto.getRi())
                .cn(dto.getCn())
                .vf(dto.getVf())
                .db(dto.getDb())
                .build();
        return toDto(feeCollectionRepository.save(e));
    }

    @Transactional
    public FeeCollectionDTO update(Long id, FeeCollectionDTO dto) {
        FeeCollection e = getEntity(id);
        e.setEn(dto.getEn());
        e.setDt(dto.getDt());
        e.setD(dto.getD());
        e.setDd(dto.getDd());
        e.setDa(dto.getDa());
        e.setF(dto.getF());
        e.setFp(dto.getFp());
        e.setPd(dto.getPd());
        e.setOd(dto.getOd());
        e.setOdd(dto.getOdd());
        e.setP(dto.getP());
        e.setRb(dto.getRb());
        e.setRi(dto.getRi());
        e.setCn(dto.getCn());
        e.setVf(dto.getVf());
        e.setDb(dto.getDb());
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
                .en(e.getEn())
                .dt(e.getDt())
                .d(e.getD())
                .dd(e.getDd())
                .da(e.getDa())
                .f(e.getF())
                .fp(e.getFp())
                .pd(e.getPd())
                .od(e.getOd())
                .odd(e.getOdd())
                .p(e.getP())
                .rb(e.getRb())
                .ri(e.getRi())
                .cn(e.getCn())
                .vf(e.getVf())
                .db(e.getDb())
                .build();
    }
}
