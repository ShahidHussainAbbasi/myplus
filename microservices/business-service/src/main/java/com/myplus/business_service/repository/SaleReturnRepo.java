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

	/**
	 * Task #15: ONE credit note by its own number, tenant-scoped — the row a printable document is built from.
	 *
	 * <p>Keyed on the note number rather than the row id because that is the document's identity: it is what
	 * the operator sees after taking a return, what a customer quotes back, and what a future returns list
	 * would show. {@code UNIQUE(organization_id, credit_note_seq)} makes it unique within a tenant.
	 *
	 * <p>Safety comes from the SCOPE PREDICATE here, not from the key being hard to guess — a row id is just
	 * as guessable as {@code CRN-000007}. The org/user filter is what makes another tenant's note return
	 * nothing, and it is inside the query so no caller can forget it.
	 */
	@Query("select r from SaleReturn r where r.creditNoteNo = :noteNo "
		+ "and (r.organizationId = :orgId or (r.organizationId is null and r.userId = :userId))")
	java.util.Optional<SaleReturn> findByCreditNoteNoScoped(@Param("noteNo") String noteNo,
			@Param("orgId") Long orgId, @Param("userId") Long userId);

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

	/**
	 * B2B-P3f: the credit notes raised against a set of invoices, for the statement's CREDIT_NOTE lines.
	 *
	 * <p>SaleReturn carries no customerId, so the statement joins on the invoice numbers it has ALREADY
	 * loaded — one batched {@code IN}, never a query per invoice. Callers must skip the call on an empty
	 * collection.
	 *
	 * <p>{@code creditAmount is not null} is the CUTOVER: only returns taken after V34 carry a value, and a
	 * pre-V34 note's value is unrecoverable. Filtering here rather than in Java keeps the rule in one place
	 * and stops a valueless row ever reaching a customer-facing document.
	 */
	@Query("select r from SaleReturn r where r.invoiceNo in :invoiceNos and r.creditAmount is not null "
		+ "and (r.organizationId = :orgId or (r.organizationId is null and r.userId = :userId))")
	List<SaleReturn> findCreditNotesForInvoices(@Param("invoiceNos") java.util.Collection<String> invoiceNos,
			@Param("orgId") Long orgId, @Param("userId") Long userId);
}
