package com.myplus.business_service.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import com.myplus.business_service.entity.Sell;

public interface ISellService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Sell, Long> {

	String createReport(List<Sell> objs) throws IOException;

	void addSell(List<Sell> dtos) throws Exception;

	List<Sell> findSellByDates(LocalDateTime sd, LocalDateTime ed, Long userId);

	List<Sell> findSellByStartDate(LocalDateTime sd, Long userId);

	List<Sell> findSellByEndDate(LocalDateTime ed, Long userId);

}
