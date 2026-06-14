package com.myplus.pharma.repository;

import com.myplus.pharma.entity.PharmacyStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PharmacyStockRepository extends JpaRepository<PharmacyStock, Long> {
    List<PharmacyStock> findByMedicineId(Long medicineId);
    List<PharmacyStock> findByExpiryDateBefore(LocalDate date);
    List<PharmacyStock> findByQuantityLessThan(int qty);
    List<PharmacyStock> findByBatchNo(String batchNo);

    @Query("SELECT COALESCE(SUM(s.quantity), 0) FROM PharmacyStock s WHERE s.medicine.id = :medicineId")
    Long sumQuantityByMedicineId(@Param("medicineId") Long medicineId);
}
