package com.myplus.business_service.service;

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

import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.entity.Purchase;


@Service
@Transactional
public class VenderService implements IVenderService {

    @Autowired
    private VenderRepo venderRepo;

    @Autowired
    private PurchaseRepo purchaseRepo;                                          // F1 (AP): FIFO across open bills

    @Autowired
    private com.myplus.business_service.service.subledger.SubledgerService subledgerService;   // shared AR/AP settlement

    @Autowired
    private IdempotencyService idempotencyService;   // Audit #5: shared money-op dedup

    @Autowired
    private AuditService auditService;   // Audit #6: append-only audit trail (via audit-service outbox)

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";
	public List<Vender> findAll() {
		// TODO Auto-generated method stub
		return venderRepo.findAll();
	}

	@Override
	public List<Vender> findScoped(Long orgId, Long userId) {
		return venderRepo.findScoped(orgId, userId);
	}

	public List<Vender> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(sort);
	}

	public List<Vender> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return venderRepo.findAllById(ids);
	}

	public <S extends Vender> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return venderRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		venderRepo.flush();
	}

	public <S extends Vender> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return venderRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Vender> entities) {
		// TODO Auto-generated method stub
		venderRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		venderRepo.deleteAllInBatch();
	}

	public Vender getOne(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.getOne(id);
	}

	public <S extends Vender> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example);
	}

	public <S extends Vender> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example,sort);
	}

	public Page<Vender> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(pageable);
	}

	public <S extends Vender> S save(S entity) {
		// TODO Auto-generated method stub
		return venderRepo.save(entity);
	}

	public Optional<Vender> findById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return venderRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		venderRepo.deleteById(id);
		
	}

	public void delete(Vender entity) {
		// TODO Auto-generated method stub
		venderRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Vender> entities) {
		// TODO Auto-generated method stub
		venderRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		venderRepo.deleteAll();
	}

	public <S extends Vender> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.findOne(example);
	}

	public <S extends Vender> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return venderRepo.findAll(example, pageable);
	}

	public <S extends Vender> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.count(example);
	}

	public <S extends Vender> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return venderRepo.exists(example);
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllByIdInBatch'");
	}

	public void deleteAllInBatch(Iterable<Vender> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllInBatch'");
	}

	public Vender getById(Long id) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getById'");
	}

	public Vender getReferenceById(Long id) {
		// TODO Auto-generated method stub
		return venderRepo.getReferenceById(id);
	}

	public <S extends Vender> List<S> saveAllAndFlush(Iterable<S> entities) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'saveAllAndFlush'");
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'deleteAllById'");
	}

	public <S extends Vender, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'findBy'");
	}

	@Override
	public void recomputePayable(Long venderId) {
		if (venderId == null) return;
		// Each purchase stores dueAmount = paid − net (negative while we owe). Payable owed = −Σ(due), floored 0.
		java.math.BigDecimal sumDue = purchaseRepo.sumDueByVendor(venderId);
		if (sumDue == null) sumDue = java.math.BigDecimal.ZERO;
		java.math.BigDecimal owed = sumDue.negate();
		if (owed.compareTo(java.math.BigDecimal.ZERO) < 0) owed = java.math.BigDecimal.ZERO;
		// Targeted update (only due_amount) — a full entity save re-wrote company_id=null when the lazy company
		// wasn't loaded (Column 'company_id' cannot be null); this also avoids a full-row rewrite.
		venderRepo.updateDueAmount(venderId, owed);
	}

	@Override
	public java.util.Map<String, Object> payVendor(Long venderId, java.math.BigDecimal amount, String method,
			java.time.LocalDate paidOn, String reference, String idempotencyKey) {
		if (venderId == null) throw new RuntimeException("venderId is required");
		if (amount == null || amount.signum() <= 0) throw new RuntimeException("A positive amount is required");
		Vender vendor = venderRepo.findById(venderId)
				.orElseThrow(() -> new RuntimeException("Vendor not found: " + venderId));

		// Audit #5: dedup a double-click/retry of this vendor payment (same key → same voucher, no second disbursement).
		final Long org = vendor.getOrganizationId();
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			java.util.Optional<String> prior = idempotencyService.find(org, "payVendor", idempotencyKey);
			if (prior.isPresent()) return replayVoucher(prior.get());
		}
		return doPayVendor(vendor, venderId, amount, method, paidOn, reference, org, idempotencyKey);
	}

	/** A replay response for an already-recorded vendor payment (same voucher, no second disbursement). */
	private java.util.Map<String, Object> replayVoucher(String voucherNo) {
		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("success", true);
		out.put("voucherNo", voucherNo);
		out.put("replay", true);
		return out;
	}

	private java.util.Map<String, Object> doPayVendor(Vender vendor, Long venderId, java.math.BigDecimal amount,
			String method, java.time.LocalDate paidOn, String reference, Long org, String idempotencyKey) {

		// The vendor's still-owing purchase bills (oldest first) as generic OpenDocs for the shared allocator.
		java.util.List<com.myplus.business_service.service.subledger.OpenDoc> docs = new java.util.ArrayList<>();
		for (Purchase bill : purchaseRepo.findOpenPurchasesByVendor(venderId)) {
			docs.add(new com.myplus.business_service.service.subledger.OpenDoc() {
				public java.math.BigDecimal outstanding() {
					java.math.BigDecimal due = bill.getDueAmount() != null ? bill.getDueAmount() : java.math.BigDecimal.ZERO;
					return due.negate();   // due = paid - net (negative while we owe)
				}
				public void apply(java.math.BigDecimal applied) {
					java.math.BigDecimal due = bill.getDueAmount() != null ? bill.getDueAmount() : java.math.BigDecimal.ZERO;
					java.math.BigDecimal paid = bill.getPaidAmount() != null ? bill.getPaidAmount() : java.math.BigDecimal.ZERO;
					bill.setPaidAmount(paid.add(applied));
					bill.setDueAmount(due.add(applied));    // moves toward 0
					bill.setUpdated(java.time.LocalDateTime.now());
					purchaseRepo.save(bill);
				}
				public String docType() { return "PURCHASE"; }
				public Long docId() { return bill.getPurchaseId(); }
				public String docNo() { return bill.getPurchaseInvoiceNo(); }
			});
		}

		// ONE shared settlement path (FIFO allocate + best-effort finance-ledger record); recomputePayable refreshes
		// the vendor's running payable and returns the fresh value for the response.
		com.myplus.business_service.service.subledger.SettleOutcome outcome = subledgerService.settle(
				"DISBURSEMENT", "VENDOR", venderId, vendor.getName(), amount, method, paidOn, reference, "BUSINESS",
				docs, () -> { this.recomputePayable(venderId);
					return venderRepo.findById(venderId).map(Vender::getDueAmount).orElse(null); });

		// Audit #5: record this payment (atomic with the allocation) so a repeat with the same key replays it.
		idempotencyService.record(org, "payVendor", idempotencyKey, outcome.voucherNo());
		// Audit #6: append-only trail (atomic capture; delivered to audit-service after commit).
		auditService.record("PAYMENT", "VENDOR", outcome.voucherNo(), amount, "vendor=" + vendor.getName());

		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("success", true);
		out.put("voucherNo", outcome.voucherNo());
		out.put("allocated", outcome.allocated());
		out.put("onAccountAdvance", outcome.onAccount());   // excess not applied to any open bill (advance to vendor)
		out.put("newDue", outcome.newDue());
		return out;
	}

}