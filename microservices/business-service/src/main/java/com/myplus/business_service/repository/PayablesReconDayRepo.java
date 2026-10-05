package com.myplus.business_service.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.PayablesReconDay;

public interface PayablesReconDayRepo extends JpaRepository<PayablesReconDay, Long> {

    Optional<PayablesReconDay> findByOrganizationIdAndReconDay(Long organizationId, LocalDate reconDay);

    /** Newest first — the panel's history and the clean-streak count. */
    List<PayablesReconDay> findTop90ByOrganizationIdOrderByReconDayDesc(Long organizationId);
}
