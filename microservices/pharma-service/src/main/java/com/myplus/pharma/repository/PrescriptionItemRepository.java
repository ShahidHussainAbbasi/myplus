package com.myplus.pharma.repository;

import com.myplus.pharma.entity.PrescriptionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {
    List<PrescriptionItem> findByPrescriptionId(Long prescriptionId);
    List<PrescriptionItem> findByItemId(Long itemId);   // slice 41 (reuse): medicine = business Item (by id)
}
