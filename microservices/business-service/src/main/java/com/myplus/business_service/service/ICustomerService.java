package com.myplus.business_service.service;

import java.util.List;

import org.springframework.data.domain.Pageable;

import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.dto.CustomerHistoryDTO;

public interface ICustomerService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Customer, Long> {

    Customer saveUpdateCustomer(CustomerHistoryDTO customerObj) throws Exception;

    /**
     * Recompute a customer's running balance owed from their invoice headers (the single source of
     * truth). Call AFTER the sale's CustomerHistory is saved, so add / edit / re-edit stay correct.
     */
    void recomputeDue(Customer customer);

    /** Tenant-scoped customers (own org + caller's pre-migration org-NULL rows). */
    List<Customer> findScoped(Long orgId, Long userId);

    /** Paged tenant-scoped customers (slice 24). */
    List<Customer> findScoped(Long orgId, Long userId, Pageable pageable);

    /** OWN customers only (role-aware) — a non-SUPER caller sees just the customers they created. */
    List<Customer> findOwnScoped(Long orgId, Long userId);


}
