package com.myplus.business_service.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.business_service.entity.CustomerHistory;

public interface CustomerHistoryRepo extends JpaRepository<CustomerHistory, Long> {

    @Query("SELECT ch FROM CustomerHistory ch LEFT JOIN FETCH ch.customer WHERE ch.userId = :userId AND ch.dated >= :sd AND ch.dated <= :ed")
    List<CustomerHistory> findByUserIdAndDateRange(
        @Param("userId") Long userId,
        @Param("sd") LocalDateTime sd,
        @Param("ed") LocalDateTime ed
    );

    // Highest invoice number issued for an org (0 if none) — the per-org invoice series counter.
    @Query("SELECT COALESCE(MAX(ch.invoiceSeq), 0) FROM CustomerHistory ch WHERE ch.organizationId = :orgId")
    Long maxInvoiceSeqForOrg(@Param("orgId") Long orgId);
}
