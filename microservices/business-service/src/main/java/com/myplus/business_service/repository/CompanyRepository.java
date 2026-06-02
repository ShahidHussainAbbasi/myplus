package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    Page<Company> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);
}
