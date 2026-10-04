package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;

public interface MarketplaceCustomerRepository extends JpaRepository<MarketplaceCustomer, Long> {

    /** uk_mkt_customer_phone. Phones are stored as digits only. */
    Optional<MarketplaceCustomer> findByPhone(String phone);
}
