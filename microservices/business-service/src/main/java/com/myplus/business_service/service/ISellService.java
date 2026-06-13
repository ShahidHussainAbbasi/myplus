package com.myplus.business_service.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import com.myplus.business_service.entity.Sell;

public interface ISellService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Sell, Long> {

	String createReport(List<Sell> objs) throws IOException;

	void addSell(List<Sell> dtos) throws Exception;

	/** Tenant-scoped sells (own org + caller's pre-migration org-NULL rows), newest first. */
	List<Sell> findScoped(Long orgId, Long userId);

	/** Paged tenant-scoped sells (slice 24), newest first. */
	List<Sell> findScoped(Long orgId, Long userId, org.springframework.data.domain.Pageable pageable);

	List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long orgId, Long userId);

	List<Sell> findSellByStartDate(LocalDateTime sd, Long orgId, Long userId);

	List<Sell> findSellByEndDate(LocalDateTime ed, Long orgId, Long userId);

}
