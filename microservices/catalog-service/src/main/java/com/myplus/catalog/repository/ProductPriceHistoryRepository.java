package com.myplus.catalog.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.catalog.entity.ProductPriceHistory;

public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, Long> {

    /** Newest first, for one product of one tenant. The product itself is scope-checked before this is asked. */
    @Query("select h from ProductPriceHistory h where h.productId = :productId "
         + "and (h.organizationId = :orgId or (h.organizationId is null and :orgId is null)) order by h.id desc")
    List<ProductPriceHistory> findForProduct(@Param("productId") Long productId, @Param("orgId") Long orgId, Pageable page);
}
