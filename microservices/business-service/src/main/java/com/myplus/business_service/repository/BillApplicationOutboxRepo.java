package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.BillApplicationOutbox;

/** FP-5b — bill shares of Pay Supplier payments (idx_bill_application_outbox_status / _org). */
public interface BillApplicationOutboxRepo extends JpaRepository<BillApplicationOutbox, Long> {

    List<BillApplicationOutbox> findTop100ByStatusOrderByIdAsc(String status);

    /** Ruling 4 — has this tenant ever paid purchases and bills in one payment? */
    long countByOrganizationId(Long organizationId);
}
