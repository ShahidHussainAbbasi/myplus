package com.myplus.clinical.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.clinical.entity.ProviderDay;

/** HMS S2 — one day's exception to a doctor's usual limit. */
public interface ProviderDayRepo extends JpaRepository<ProviderDay, ProviderDay.Key> {

    List<ProviderDay> findByOrganizationIdAndVisitDate(Long organizationId, LocalDate visitDate);
}
