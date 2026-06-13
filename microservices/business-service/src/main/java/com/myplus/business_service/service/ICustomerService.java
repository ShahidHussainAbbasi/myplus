package com.myplus.business_service.service;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.dto.CustomerHistoryDTO;

public interface ICustomerService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Customer, Long> {

    Customer saveUpdateCustomer(CustomerHistoryDTO customerObj) throws Exception;

    /** Tenant-scoped customers (own org + caller's pre-migration org-NULL rows). */
    List<Customer> findScoped(Long orgId, Long userId);

    /** Paged tenant-scoped customers (slice 24). */
    List<Customer> findScoped(Long orgId, Long userId, Pageable pageable);


}
