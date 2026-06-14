package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Vender;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VenderRepository extends JpaRepository<Vender, Long> {
    Page<Vender> findByUserId(Long userId, Pageable pageable);
    Page<Vender> findByCompanyId(Long companyId, Pageable pageable);
    long countByUserId(Long userId);
}
