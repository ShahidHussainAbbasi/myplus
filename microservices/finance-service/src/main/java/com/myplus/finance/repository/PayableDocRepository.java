package com.myplus.finance.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.finance.entity.PayableDoc;

/** FP-1 — the payables subledger, always read inside one tenant (idx_payable_doc_party / uq_payable_doc_source). */
public interface PayableDocRepository extends JpaRepository<PayableDoc, Long> {

    Optional<PayableDoc> findByOrganizationIdAndSourceAndSourceRef(Long organizationId, String source, String sourceRef);

    /** Σ open across the tenant's live documents (a void contributes nothing). */
    @Query("select coalesce(sum(d.amount - d.paid), 0) from PayableDoc d "
         + "where d.organizationId = :org and d.status = 'OPEN'")
    BigDecimal sumOpen(@Param("org") Long org);

    /**
     * Per supplier, Σ(amount − paid) over every non-void document — an overpaid line counts negative, so a supplier
     * paid more than billed nets below zero (an ADVANCE). Business shows the same net, floored at zero.
     */
    @Query("select d.partyId, max(d.partyName), sum(d.amount - d.paid) from PayableDoc d "
         + "where d.organizationId = :org and d.status <> 'VOID' group by d.partyId")
    List<Object[]> netBySupplier(@Param("org") Long org);

    @Query("select d from PayableDoc d where d.organizationId = :org and d.status = 'OPEN' order by d.partyName, d.docDate")
    List<PayableDoc> findOpen(@Param("org") Long org);
}
