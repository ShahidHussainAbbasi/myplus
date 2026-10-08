package com.myplus.marketplace.multiseller.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.marketplace.multiseller.entity.MarketplaceHoliday;

/** MKT-2f — bank holidays (V34). Tens of rows a year: read whole. */
public interface MarketplaceHolidayRepository extends JpaRepository<MarketplaceHoliday, LocalDate> {

    List<MarketplaceHoliday> findAllByOrderByHolidayDateAsc();
}
