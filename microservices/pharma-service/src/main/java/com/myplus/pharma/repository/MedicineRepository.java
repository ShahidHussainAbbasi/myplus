package com.myplus.pharma.repository;

import com.myplus.pharma.entity.Medicine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicineRepository extends JpaRepository<Medicine, Long> {
    Page<Medicine> findByNameContainingIgnoreCase(String name, Pageable pageable);
    Page<Medicine> findByCategoryId(Long categoryId, Pageable pageable);
    List<Medicine> findByIsActiveTrue();
    Page<Medicine> findByIsActiveTrue(Pageable pageable);
    List<Medicine> findByRequiresPrescriptionTrue();
    Page<Medicine> findByNameContainingIgnoreCaseAndCategoryId(String name, Long categoryId, Pageable pageable);
}
