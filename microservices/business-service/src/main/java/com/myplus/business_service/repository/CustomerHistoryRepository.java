package com.myplus.business_service.repository;

import com.myplus.business_service.entity.CustomerHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerHistoryRepository extends JpaRepository<CustomerHistory, Long> {
    Page<CustomerHistory> findByCustomerCustomerId(Long customerId, Pageable pageable);
}
