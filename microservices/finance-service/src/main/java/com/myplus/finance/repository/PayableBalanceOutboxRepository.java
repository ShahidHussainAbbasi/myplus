package com.myplus.finance.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.finance.entity.PayableBalanceOutbox;

/** FP-4c — supplier-figure notices on their way to business (idx_payable_balance_outbox_status). */
public interface PayableBalanceOutboxRepository extends JpaRepository<PayableBalanceOutbox, Long> {

    List<PayableBalanceOutbox> findTop100ByStatusOrderByIdAsc(String status);

    /**
     * A real user of this tenant, to act as on a notice queued with none (the V12 seed: a supplier's documents carry no
     * user). business authenticates only a call that names a user, so a user-less notice would be refused for ever.
     * The tenant's most recent journal author is someone business already knows under that organization.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT e.user_id FROM journal_entries e WHERE e.organization_id = :org "
            + "AND e.user_id IS NOT NULL ORDER BY e.id DESC LIMIT 1", nativeQuery = true)
    Long anyUserOf(@org.springframework.data.repository.query.Param("org") Long org);
}
