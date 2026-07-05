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

    @Autowired
    CustomerHistoryRepo customerHistoryRepo;

    @Autowired
    private AppUtil appUtil;

	@Autowired
	RequestUtil requestUtil;

	@Autowired(required = false)
	com.myplus.commerce.contracts.client.FinanceClient financeClient;   // shared payment ledger (AR subledger)

	private static final org.slf4j.Logger PAY_LOG = org.slf4j.LoggerFactory.getLogger(CustomerService.class);

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

	@Override
	public List<Customer> findScoped(Long orgId, Long userId, org.springframework.data.domain.Pageable pageable) {
		return customerRepo.findScoped(orgId, userId, pageable);
	}

	@Override
	public List<Customer> findOwnScoped(Long orgId, Long userId) {
		return customerRepo.findOwnScoped(orgId, userId);
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

		return customerObj;
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
			java.time.LocalDate paidOn, String reference) {
		if (customerId == null) throw new RuntimeException("customerId is required");
		if (amount == null || amount.signum() <= 0) throw new RuntimeException("A positive amount is required");

		Customer customer = this.findById(customerId)
				.orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));

		// FIFO-allocate the receipt across the customer's still-owing invoices (oldest first).
		java.util.List<com.myplus.commerce.contracts.dto.PaymentAllocationRef> allocations = new java.util.ArrayList<>();
		java.math.BigDecimal remaining = amount;
		for (CustomerHistory inv : customerHistoryRepo.findOpenInvoicesByCustomer(customerId)) {
			if (remaining.signum() <= 0) break;
			java.math.BigDecimal due = inv.getDueAmount() != null ? inv.getDueAmount() : java.math.BigDecimal.ZERO;
			java.math.BigDecimal outstanding = due.negate();   // due = paid − bill (negative while owing)
			if (outstanding.signum() <= 0) continue;
			java.math.BigDecimal applied = remaining.min(outstanding);
			java.math.BigDecimal paid = inv.getPaidAmount() != null ? inv.getPaidAmount() : java.math.BigDecimal.ZERO;
			inv.setPaidAmount(paid.add(applied));
			inv.setDueAmount(due.add(applied));                // moves toward 0
			inv.setUpdated(LocalDateTime.now());
			customerHistoryRepo.save(inv);
			allocations.add(com.myplus.commerce.contracts.dto.PaymentAllocationRef.builder()
					.docType("INVOICE").docId(inv.getCustomer_history_id()).docNo(inv.getInvoiceNo()).amount(applied).build());
			remaining = remaining.subtract(applied);
		}

		// Recompute the customer's running balance from the (now-updated) invoice headers.
		this.recomputeDue(customer);

		// Record the receipt in the shared ledger — best-effort: the settlement is already applied, a ledger
		// hiccup must never block taking the customer's money (reconcile later).
		String receiptNo = null;
		try {
			if (financeClient != null) {
				com.myplus.commerce.contracts.dto.PaymentRecordResult res = financeClient.recordPayment(
						com.myplus.commerce.contracts.dto.PaymentRecordRequest.builder()
								.direction("RECEIPT").partyType("CUSTOMER").partyId(customerId).partyName(customer.getName())
								.amount(amount).method(method).paidOn(paidOn).reference(reference)
								.sourceModule("BUSINESS").allocations(allocations).build());
				receiptNo = res != null ? res.getReceiptNo() : null;
			}
		} catch (Exception ex) {
			PAY_LOG.warn("finance ledger record failed for customer {} (settlement applied; reconcile later)", customerId, ex);
		}

		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("success", true);
		out.put("receiptNo", receiptNo);
		out.put("allocated", amount.subtract(remaining));
		out.put("onAccountCredit", remaining);   // excess not applied to any open invoice (customer due floors at 0)
		out.put("newDue", customer.getDueAmount());
		return out;
	}

}