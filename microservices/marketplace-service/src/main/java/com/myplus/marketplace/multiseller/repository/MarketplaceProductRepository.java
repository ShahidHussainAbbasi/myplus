package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;

public interface MarketplaceProductRepository extends JpaRepository<MarketplaceProduct, Long> {

    Optional<MarketplaceProduct> findByIdentityKey(String identityKey);

    /**
     * Operator search by name. {@code ContainingIgnoreCase} becomes {@code LIKE %q%} and cannot use
     * idx_mkt_product_name; acceptable for an operator screen over a catalogue measured in thousands, paged at 100.
     */
    Page<MarketplaceProduct> findByCanonicalNameContainingIgnoreCaseOrderByCanonicalNameAsc(String q, Pageable page);

    Page<MarketplaceProduct> findAllByOrderByCanonicalNameAsc(Pageable page);
}
