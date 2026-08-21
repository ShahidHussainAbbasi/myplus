package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class CustomerService implements ICustomerService{

    @Autowired
    CustomerRepo customerRepo;

    /**
     * INST-1 — installment plans, when the tenant sells on terms.
     *
     * <p>{@code required = false} on all three, deliberately: every existing hand-built test of this class
     * reflection-injects only the fields it knows about, and a class that silently leaves a new field null is
     * this codebase's recurring trap (three times on {@code SagaSellService} alone). Making them optional
     * means the installment path degrades to today's behaviour — invoices only — rather than an NPE inside a
     * receipt. The null checks at the call site are the visible half of the same decision.
     */
    @Autowired(required = false)
    com.myplus.business_service.service.InstallmentPlanService installmentPlanService;

    @Autowired(required = false)
    com.myplus.business_service.repository.InstallmentPlanRepo installmentPlanRepo;

    @Autowired(required = false)
    com.myplus.common.settings.SettingsService settingsService;

    @Autowired
    CustomerHistoryRepo customerHistoryRepo;

    @Autowired
    private AppUtil appUtil;

	@Autowired
	RequestUtil requestUtil;

	@Autowired
	IdempotencyService idempotencyService;   // Audit #5: shared money-op dedup

	@Autowired
	PeriodLockGuard periodLockGuard;   // period close: reject receipts dated in a locked period

	@Autowired
	PartyBridgeService partyBridgeService;   // P1: link the customer to the shared party master (best-effort, once)

	@Autowired
	AuditService auditService;   // Audit #6: append-only audit trail (via audit-service outbox)

	@Autowired
	com.myplus.common.subledger.SubledgerService subledgerService;   // shared AR/AP settlement

	// @Autowired
	// ObjectMapperUtils objectMapperUtils;

	public List<Customer> findAll() {
return customerRepo.findAll();
	}

	public List<Customer> findAll(Sort sort) {
return customerRepo.findAll(sort);
	}

	public List<Customer> findAllById(Iterable<Long> ids) {
return customerRepo.findAllById(ids);
	}

	public <S extends Customer> List<S> saveAll(Iterable<S> entities) {
return customerRepo.saveAll(entities);
	}

	public void flush() {
customerRepo.flush();
	}

	public <S extends Customer> S saveAndFlush(S entity) {
return customerRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Customer> entities) {
customerRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
customerRepo.deleteAllInBatch();
	}

	public Customer getOne(Long id) {
return customerRepo.getOne(id);
	}

	public <S extends Customer> List<S> findAll(Example<S> example) {
return customerRepo.findAll(example);
	}

	public <S extends Customer> List<S> findAll(Example<S> example, Sort sort) {
return customerRepo.findAll(example,sort);
	}

	public Page<Customer> findAll(Pageable pageable) {
return customerRepo.findAll(pageable);
	}

	public <S extends Customer> S save(S entity) {
return customerRepo.save(entity);
	}

	public Optional<Customer> findById(Long id) {
return customerRepo.findById(id);
	}

	public boolean existsById(Long id) {
return customerRepo.existsById(id);
	}

	public long count() {
return customerRepo.count();
	}

	public void deleteById(Long id) {
customerRepo.deleteById(id);
		
	}

	public void delete(Customer entity) {
customerRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Customer> entities) {
customerRepo.deleteAll(entities);
	}

	public void deleteAll() {
customerRepo.deleteAll();
	}

	public <S extends Customer> Optional<S> findOne(Example<S> example) {
return customerRepo.findOne(example);
	}

	public <S extends Customer> Page<S> findAll(Example<S> example, Pageable pageable) {
return customerRepo.findAll(example, pageable);
	}

	public <S extends Customer> long count(Example<S> example) {
return customerRepo.count(example);
	}

	public <S extends Customer> boolean exists(Example<S> example) {
return customerRepo.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		customerRepo.deleteAllByIdInBatch(ids);
	}

	public void deleteAllInBatch(Iterable<Customer> entities) {
				customerRepo.deleteAllInBatch(entities);

	}

	public Customer getById(Long id) {
		return customerRepo.getReferenceById(id);
	}

	public Customer getReferenceById(Long id) {
		return customerRepo.getReferenceById(id);
	}

	public <S extends Customer> List<S> saveAllAndFlush(Iterable<S> entities) {
		return customerRepo.saveAllAndFlush(entities);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		customerRepo.deleteAllById(ids);
	}

	public <S extends Customer, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return customerRepo.findBy(example, queryFunction);
	}

	public List<Customer> findByUserId(Long userId) {
		return customerRepo.findByUserId(userId);
	}

	@Override
	public List<Customer> findScoped(Long orgId, Long userId) {
		return customerRepo.findScoped(orgId, userId);
	}

	/**
	 * O7 D5 — the scoped single read, exposed on the service so callers outside this package can reach it
	 * without an internal endpoint reaching around into the repository. {@code CreditStandingService} already
	 * used the repository method directly; the internal receipts endpoint takes a customer id straight off a
	 * service-to-service body, which is exactly the shape D2's leak had.
	 */
	@Override
	public java.util.Optional<Customer> findByIdScoped(Long customerId, Long orgId, Long userId) {
		if (customerId == null) return java.util.Optional.empty();
		return customerRepo.findByIdScoped(customerId, orgId, userId);
	}

	@Override
	public List<Customer> findScoped(Long orgId, Long userId, org.springframework.data.domain.Pageable pageable) {
		return customerRepo.findScoped(orgId, userId, pageable);
	}

	@Override
	public List<Customer> findOwnScoped(Long orgId, Long userId) {
		return customerRepo.findOwnScoped(orgId, userId);
	}

	/** O7 D2d — the rep's territory: their assigned outlets plus every unassigned one. */
	@Override
	public List<Customer> findOutletsForRep(Long orgId, Long repUserId) {
		return customerRepo.findOutletsForRep(orgId, repUserId);
	}

	/** O7 D2d — every outlet in the org (whole-org viewers only; the controller decides who qualifies). */
	@Override
	public List<Customer> findOutletsForOrg(Long orgId) {
		return customerRepo.findOutletsForOrg(orgId);
	}


	public Customer saveUpdateCustomer(CustomerHistoryDTO dto) throws Exception {

		Customer customerObj = dto.getCustomer().getCustomerId() != null ? this.getReferenceById(dto.getCustomer().getCustomerId()) : new Customer();

		AuthenticatedUser actor = requestUtil.getCurrentUser();

		if(appUtil.isEmptyOrNull(customerObj.getCustomerId())){

			// New (or not-yet-identified) customer: populate identity, then try to match an existing row
			// by name/contact so a repeat customer isn't duplicated.
			customerObj.setUserId(actor.getUserId());
			if (dto.getCustomer().getContact() != null) {
				customerObj.setContact(dto.getCustomer().getContact());
			}
			if (dto.getCustomer().getName() != null) {
				customerObj.setName(dto.getCustomer().getName());
			}

			// build the dup-check probe from the fully-populated object (was constructed above before
			// these setters ran — worked only because Example holds a live reference; brittle).
			Example<Customer> example = Example.of(customerObj);
			customerObj = this.findOne(example).orElse(customerObj);

			// brand-new customer: seed the running balance at zero so the non-null-ready column has a
			// value; recomputeDue() sets the real figure once this sale's invoice header is saved.
			if (appUtil.isEmptyOrNull(customerObj.getCustomerId()) && customerObj.getDueAmount() == null) {
				customerObj.setDueAmount(java.math.BigDecimal.ZERO);
			}
			if (dto.getCustomer().getDueDate() != null) {
				customerObj.setDueDate(dto.getCustomer().getDueDate());
			}
		}

		// NOTE: the customer's running balance (dueAmount) is deliberately NOT computed here. It is
		// recomputed from the customer's invoice headers by recomputeDue() AFTER the CustomerHistory for
		// this sale is saved — so a new sale, an edit, and a re-edit all stay correct (no lossy in-place
		// accumulation). See SellController.addSell / updateSell.

		customerObj.setDated(LocalDateTime.now());
		customerObj.setUpdated(LocalDateTime.now());
		if (actor != null) {
			if (customerObj.getUserId() == null) customerObj.setUserId(actor.getUserId()); // audit
			customerObj.setOrganizationId(actor.getOrganizationId());                       // tenant scope
		}
		this.save(customerObj);

		// P1: link to the shared party master (best-effort, once). No-op for an already-bridged repeat customer, so
		// the hot path pays nothing; only a brand-new customer's first save makes the one upsert call.
		partyBridgeService.bridgeCustomer(customerObj);

		return customerObj;
	}

	/**
	 * INST-1 — restate every collectable plan's invoice from its installment rows.
	 *
	 * <p>Runs inside the settle callback, immediately before {@code recomputeDue}, because that method sums
	 * the customer's balance from INVOICE headers. Money allocated to installments is invisible to it until
	 * the invoice has been restated.
	 *
	 * <p>Silently does nothing when the installment beans are absent — the feature degrades to today's
	 * behaviour rather than failing a receipt.
	 */
	private void syncPlansToInvoices(Long org, Long customerId) {
		if (installmentPlanService == null || installmentPlanRepo == null) return;
		for (com.myplus.business_service.entity.InstallmentPlan plan
				: installmentPlanRepo.findCollectableByCustomer(org, customerId)) {
			installmentPlanService.syncInvoiceFromPlan(plan, customerHistoryRepo);
		}
	}

	@Override
	public void recomputeDue(Customer customer) {
		if (customer == null || customer.getCustomerId() == null) return;
		// Each invoice header stores dueAmount = (paid − bill): negative while the customer still owes.
		// Running balance owed = Σ(bill − paid) = −Σ(dueAmount), floored at 0 (this app keeps no credit).
		java.math.BigDecimal sumDue = customerHistoryRepo.sumDueByCustomer(customer.getCustomerId());
		if (sumDue == null) sumDue = java.math.BigDecimal.ZERO;
		java.math.BigDecimal owed = sumDue.negate();
		if (owed.compareTo(java.math.BigDecimal.ZERO) < 0) owed = java.math.BigDecimal.ZERO;
		customer.setDueAmount(owed);
		customerRepo.save(customer);
	}

	@Override
	@jakarta.transaction.Transactional
	public java.util.Map<String, Object> receivePayment(Long customerId, java.math.BigDecimal amount, String method,
			java.time.LocalDate paidOn, String reference, String idempotencyKey) {
		if (customerId == null) throw new RuntimeException("customerId is required");
		if (amount == null || amount.signum() <= 0) throw new RuntimeException("A positive amount is required");
		// Period close: a receipt is dated on paidOn (or today) — that period must be open.
		periodLockGuard.assertOpen(paidOn != null ? paidOn : java.time.LocalDate.now());

		Customer customer = this.findById(customerId)
				.orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));

		// Audit #5: dedup a double-click/retry of this receipt. A prior submit with the same key returns the SAME
		// receipt (no second allocation). Blank key (legacy) → guard disabled.
		final Long org = customer.getOrganizationId();
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			java.util.Optional<String> prior = idempotencyService.find(org, "receivePayment", idempotencyKey);
			if (prior.isPresent()) return replayPayment(prior.get());
		}
		return doReceivePayment(customer, customerId, amount, method, paidOn, reference, org, idempotencyKey);
	}

	/** A replay response for an already-recorded receipt (same receipt, no second charge). */
	private java.util.Map<String, Object> replayPayment(String receiptNo) {
		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("success", true);
		out.put("receiptNo", receiptNo);
		out.put("replay", true);
		return out;
	}

	private java.util.Map<String, Object> doReceivePayment(Customer customer, Long customerId, java.math.BigDecimal amount,
			String method, java.time.LocalDate paidOn, String reference, Long org, String idempotencyKey) {

		// INST-1 — a customer can owe BOTH: accessories on an ordinary invoice and a handset on a plan. One
		// receipt must clear both, in a defensible order.
		//
		// The invoice that CARRIES a plan is excluded from the invoice stream below, because the plan already
		// represents that debt. Offering it twice would let one payment over-clear the balance — the same
		// money counted against the invoice and against its own installments.
		java.util.List<com.myplus.common.subledger.OpenDoc> planDocs =
				(installmentPlanService == null)
						? java.util.Collections.emptyList()
						: installmentPlanService.openInstallments(org, customerId);
		java.util.Set<String> planInvoiceNos = (installmentPlanService == null)
				? java.util.Collections.emptySet()
				: new java.util.HashSet<>(installmentPlanService.planInvoiceNumbers(org, customerId));

		// The customer's still-owing invoices (oldest first) as generic OpenDocs for the shared allocator.
		java.util.List<com.myplus.common.subledger.OpenDoc> docs = new java.util.ArrayList<>();
		for (CustomerHistory inv : customerHistoryRepo.findOpenInvoicesByCustomer(customerId)) {
			if (inv.getInvoiceNo() != null && planInvoiceNos.contains(inv.getInvoiceNo())) continue;
			docs.add(new com.myplus.common.subledger.OpenDoc() {
				public java.math.BigDecimal outstanding() {
					java.math.BigDecimal due = inv.getDueAmount() != null ? inv.getDueAmount() : java.math.BigDecimal.ZERO;
					return due.negate();   // due = paid - bill (negative while owing)
				}
				public void apply(java.math.BigDecimal applied) {
					java.math.BigDecimal due = inv.getDueAmount() != null ? inv.getDueAmount() : java.math.BigDecimal.ZERO;
					java.math.BigDecimal paid = inv.getPaidAmount() != null ? inv.getPaidAmount() : java.math.BigDecimal.ZERO;
					inv.setPaidAmount(paid.add(applied));
					inv.setDueAmount(due.add(applied));    // moves toward 0
					inv.setUpdated(LocalDateTime.now());
					customerHistoryRepo.save(inv);
				}
				public String docType() { return "INVOICE"; }
				public Long docId() { return inv.getCustomer_history_id(); }
				public String docNo() { return inv.getInvoiceNo(); }
			});
		}

		// INST-1 — compose the two streams into the one ordered list the allocator walks. The allocator is
		// UNCHANGED: it already applies money FIFO across whatever OpenDocs it is handed, so the only new
		// logic is WHICH list and in what order. No second allocator, no second settlement path — the third
		// copy of allocate-and-record is exactly what SubledgerService was extracted to prevent.
		java.util.List<com.myplus.common.subledger.OpenDoc> allDocs =
				(installmentPlanService == null) ? docs
						: installmentPlanService.composeOpenDocs(planDocs, docs,
								settingsService == null ? null
										: settingsService.getChoice("pos.installment.allocationOrder",
												java.util.Set.of(
														com.myplus.business_service.service.InstallmentPlanService.ORDER_BY_DUE_DATE,
														com.myplus.business_service.service.InstallmentPlanService.ORDER_INSTALLMENTS_FIRST,
														com.myplus.business_service.service.InstallmentPlanService.ORDER_INVOICES_FIRST),
												com.myplus.business_service.service.InstallmentPlanService.ORDER_BY_DUE_DATE));

		// ONE shared settlement path (FIFO allocate + best-effort finance-ledger record); recomputeDue refreshes
		// the customer's running balance and returns the fresh due for the response.
		com.myplus.common.subledger.SettleOutcome outcome = subledgerService.settle(
				"RECEIPT", "CUSTOMER", customerId, customer.getName(), amount, method, paidOn, reference, "BUSINESS",
				allDocs, () -> {
					// INST-1 — push each plan's payments down onto the invoice that carries it, BEFORE
					// recomputeDue reads the invoice headers. Without this the allocator reduces the
					// installments and nothing tells the invoice, so a customer pays and their outstanding
					// balance does not move. Design D5's invariant does not maintain itself.
					syncPlansToInvoices(org, customerId);
					this.recomputeDue(customer);
					return customer.getDueAmount();
				});

		// A plan whose rows all reached zero is COMPLETED. Restated from the rows rather than tracked as the
		// money lands, so the status can never disagree with the schedule beneath it.
		if (installmentPlanService != null && installmentPlanRepo != null) {
			for (com.myplus.business_service.entity.InstallmentPlan plan
					: installmentPlanRepo.findCollectableByCustomer(org, customerId)) {
				installmentPlanService.refreshStatus(plan);
			}
		}

		// Audit #5: record this receipt (atomic with the allocation) so a repeat with the same key replays it.
		idempotencyService.record(org, "receivePayment", idempotencyKey, outcome.voucherNo());
		// Audit #6: append-only trail (atomic capture; delivered to audit-service after commit).
		auditService.record("RECEIPT", "CUSTOMER", outcome.voucherNo(), amount, "customer=" + customer.getName());

		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("success", true);
		out.put("receiptNo", outcome.voucherNo());
		out.put("allocated", outcome.allocated());
		out.put("onAccountCredit", outcome.onAccount());   // excess not applied to any open invoice (due floors 0)
		out.put("newDue", outcome.newDue());
		return out;
	}

}