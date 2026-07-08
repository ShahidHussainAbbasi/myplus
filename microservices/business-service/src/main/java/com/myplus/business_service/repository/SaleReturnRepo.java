package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.SaleReturn;

/** SF-11: tenant-scoped reads for the sale-return / credit-note audit log (org + user NULL-fallback). */
public interface SaleReturnRepo extends JpaRepository<SaleReturn, Long> {

	@Query("select r from SaleReturn r where (r.organizationId = :orgId or (r.organizationId is null and r.userId = :userId)) "
		+ "order by r.dated desc")
	List<SaleReturn> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
