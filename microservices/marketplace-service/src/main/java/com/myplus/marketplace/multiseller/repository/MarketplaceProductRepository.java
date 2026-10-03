package com.myplus.marketplace.multiseller.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;

public interface MarketplaceProductRepository extends JpaRepository<MarketplaceProduct, Long> {

    Optional<MarketplaceProduct> findByIdentityKey(String identityKey);

    /**
     * Operator search by name. {@code ContainingIgnoreCase} becomes {@code LIKE %q%} and cannot use
     * idx_mkt_product_name; acceptable for an operator screen over a catalogue measured in thousands, paged at 100.
     */
    Page<MarketplaceProduct> findByCanonicalNameContainingIgnoreCaseOrderByCanonicalNameAsc(String q, Pageable page);

    Page<MarketplaceProduct> findAllByOrderByCanonicalNameAsc(Pageable page);

    /**
     * MKT-1d public search: products a customer may see (APPROVED, not regulated) that have at least one LIVE offer
     * row, matched on name, brand or model. {@code q} is already lower-cased, LIKE-escaped with '!' and wrapped in
     * '%' by the caller, or null for "browse everything". '!' and not backslash: MySQL reads a backslash inside a
     * string literal as an escape, so {@code ESCAPE '\'} breaks the statement.
     */
    @Query(value = "select p from MarketplaceProduct p where p.approvalStatus = 'APPROVED' and p.regulatedStatus = 'NONE'"
            + " and (:q is null or lower(p.canonicalName) like :q escape '!' or lower(p.brand) like :q escape '!'"
            + " or lower(p.model) like :q escape '!')"
            + " and exists (select 1 from MarketplaceOfferProjection x where x.mktProductId = p.id and x.status = 'LIVE')"
            + " order by p.canonicalName asc, p.id asc")
    Page<MarketplaceProduct> publicSearch(@Param("q") String q, Pageable page);
}
