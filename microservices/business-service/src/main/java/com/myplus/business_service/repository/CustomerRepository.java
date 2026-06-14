package com.myplus.business_service.repository;

import com.myplus.business_service.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Page<Customer> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);
    Page<Customer> findByUserIdAndDueAmountGreaterThan(Long userId, BigDecimal dueAmount, Pageable pageable);
}
