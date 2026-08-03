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

	/** Audit #3: has any return already been recorded against this invoice? (void is blocked if so). */
	@Query("select count(r) from SaleReturn r where r.invoiceNo = :invoiceNo "
		+ "and (r.organizationId = :orgId or (r.organizationId is null and r.userId = :userId))")
	long countByInvoiceScoped(@Param("invoiceNo") String invoiceNo, @Param("orgId") Long orgId, @Param("userId") Long userId);

	/**
	 * B2B-P3c (#1): next credit-note sequence for this org. MAX+1 inside the return's transaction, exactly as
	 * invoice numbers work; UNIQUE(organization_id, credit_note_seq) is what actually prevents a duplicate.
	 */
	@Query("select coalesce(max(r.creditNoteSeq), 0) from SaleReturn r where r.organizationId = :orgId")
	long maxCreditNoteSeqForOrg(@Param("orgId") Long orgId);
}
