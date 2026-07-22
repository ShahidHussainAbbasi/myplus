package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Transactional;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.stereotype.Service;

import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Purchase;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;

@Service
@Transactional
public class PurchaseService implements IPurchaseService{

    @Autowired
    PurchaseRepo purchaseRepo;

/*    @Autowired
    IBatchService batchService;
*/
    @Autowired
    RequestUtil requestUtil;

    @Autowired
    AppUtil appUtil;

    @Autowired
    com.myplus.business_service.config.TradeSagaProperties tradeSagaProperties;

    @Autowired
    com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

    @Autowired
    com.myplus.commerce.contracts.client.CatalogClient catalogClient;   // Option B: re-price the product on receive

    @Autowired
    IVenderService venderService;                                       // F1 (AP): refresh vendor payable on purchase

    @Autowired
    GlOutboxService glOutboxService;   // #4: durable GL posting via the outbox (replaces direct FinanceClient)

    @Autowired
    IdempotencyService idempotencyService;   // Audit #5: shared money-op dedup

    @Autowired
    AuditService auditService;   // Audit #6: append-only audit trail (via audit-service outbox)

    @Autowired
    TaxService taxService;   // Phase B: input-tax policy (Purchase tax toggle + rate)

    @Autowired
    PeriodLockGuard periodLockGuard;   // period close: reject bills/edits/voids dated in a locked period

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PurchaseService.class);

    ModelMapper modelMapper = new ModelMapper();
    
	public List<Purchase> findAll() {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll();
	}

	@Override
	public List<Purchase> findScoped(Long orgId, Long userId) {
		return purchaseRepo.findScoped(orgId, userId);
	}

	@Override
	public List<Purchase> findOwnScoped(Long orgId, Long userId) {
		return purchaseRepo.findOwnScoped(orgId, userId);
	}

	@Override
	public List<Purchase> findScopedByStores(Long orgId, java.util.Collection<Long> storeIds) {
		return purchaseRepo.findScopedByStores(orgId, storeIds);
	}

	@Override
	public List<Purchase> findOwnScopedByStores(Long orgId, Long userId, java.util.Collection<Long> storeIds) {
		return purchaseRepo.findOwnScopedByStores(orgId, userId, storeIds);
	}

	public List<Purchase> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(sort);
	}

	public List<Purchase> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAllById(ids);
	}

	public <S extends Purchase> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return purchaseRepo.saveAll(entities);
	}

	public void flush() {
		// TODO Auto-generated method stub
		purchaseRepo.flush();
	}

	public <S extends Purchase> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return purchaseRepo.saveAndFlush(entity);
	}

	public void deleteInBatch(Iterable<Purchase> entities) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteInBatch(entities);
	}

	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAllInBatch();
	}

	public Purchase getOne(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.getOne(id);
	}

	public <S extends Purchase> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example);
	}

	public <S extends Purchase> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example,sort);
	}

	public Page<Purchase> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(pageable);
	}

	public <S extends Purchase> S save(S entity) {
		// TODO Auto-generated method stub
		return purchaseRepo.save(entity);
	}

	public Optional<Purchase> findById(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.findById(id);
	}

	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return purchaseRepo.existsById(id);
	}

	public long count() {
		// TODO Auto-generated method stub
		return purchaseRepo.count();
	}

	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteById(id);
		
	}

	public void delete(Purchase entity) {
		// TODO Auto-generated method stub
		purchaseRepo.delete(entity);
		
	}

	public void deleteAll(Iterable<? extends Purchase> entities) {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAll(entities);
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
		purchaseRepo.deleteAll();
	}

	public <S extends Purchase> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.findOne(example);
	}

	public <S extends Purchase> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return purchaseRepo.findAll(example, pageable);
	}

	public <S extends Purchase> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.count(example);
	}

	public <S extends Purchase> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return purchaseRepo.exists(example);
	}

	@Override
	@Transactional
	public Purchase addPurchase(PurchaseDTO dto) throws Exception {
		AuthenticatedUser user = requestUtil.getCurrentUser();
		dto.setUserId(user.getUserId());
		periodLockGuard.assertOpen(java.time.LocalDate.now());   // period close: a new bill is a today-dated entry

		// Audit #5: dedup a double-click/retry of this purchase (same key → the SAME purchase, no second stock-in or payable).
		final Long org = user.getOrganizationId();
		final String idemKey = dto.getIdempotencyKey();
		if (idemKey != null && !idemKey.isBlank()) {
			java.util.Optional<String> prior = idempotencyService.find(org, "addPurchase", idemKey);
			if (prior.isPresent()) return replayPurchase(prior.get());   // sequential double-submit → same bill
		}
		return doAddPurchase(dto, user, org, idemKey);
	}

	/** A replay for an already-recorded purchase (the same bill), by its stored purchaseId; null if not yet visible. */
	private Purchase replayPurchase(String purchaseId) {
		return purchaseId == null ? null : purchaseRepo.findById(Long.valueOf(purchaseId)).orElse(null);
	}

	private Purchase doAddPurchase(PurchaseDTO dto, AuthenticatedUser user, Long org, String idemKey) throws Exception {
		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		Purchase obj = modelMapper.map(dto, Purchase.class);
		obj.setUpdated(obj.getUpdated()!=null?obj.getUpdated():LocalDateTime.now());
		obj.setDated(LocalDateTime.now());
		obj.setUserId(user.getUserId());                  // audit
		obj.setOrganizationId(user.getOrganizationId());  // tenant scope
		obj.setStoreId(user.getActiveLocationId());       // multi-location: store this purchase was recorded at

		// M3c.4b (slice 84): the purchase is self-describing — copy its batch/rate snapshot straight off the DTO
		// (StockDTO scalar types already match Purchase; bexpDate parsed via AppUtil) instead of going through a local
		// Stock entity. Inventory stays authoritative for on-hand (pushed below).
		// M4e.d (slice 106): productId-native — the purchase form submits productId; the legacy itemId field + its
		// ensureMapped(itemId) auto-map fallback are gone (Item entity retired).
		obj.setProductId(dto.getProductId());
		StockDTO snap = dto.getStock();
		if (snap != null) {
			obj.setBatchNo(snap.getBatchNo());
			obj.setBpurchaseRate(snap.getBpurchaseRate());
			obj.setBsellRate(snap.getBsellRate());
			obj.setBpurchaseDiscount(snap.getBpurchaseDiscount());
			obj.setBsellDiscount(snap.getBsellDiscount());
			obj.setBpurchaseDiscountType(snap.getBpurchaseDiscountType());
			obj.setBsellDiscountType(snap.getBsellDiscountType());
			obj.setBexpDate(appUtil.toLocalDateOrNull(snap.getBexpDate()));
		}
		// F1 (AP): the bill's payment position. The vendor bill = totalAmount (qty × purchase rate = what we owe);
		// NOTE netAmount here is the sell-vs-cost PROFIT, not the payable. paidAmount defaults to the full bill
		// (a cash purchase); dueAmount = paid − bill (negative while we still owe). venderId + paidAmount via the mapper.
		// Tax register Phase B: input tax when the org's "Purchase tax" toggle is on. totalAmount stays the goods/net
		// value; the vendor bill you owe = net + input tax. Off (or 0 rate) → tax 0 → bill = net (unchanged behaviour).
		java.math.BigDecimal net = nz(obj.getTotalAmount());
		java.math.BigDecimal tax = java.math.BigDecimal.ZERO;
		var taxSetting = taxService.settingsFor(org);
		if (taxSetting != null && Boolean.TRUE.equals(taxSetting.getInputTaxEnabled())) {
			java.math.BigDecimal rate = TaxService.resolveRate(dto.getTaxRate(), taxSetting);
			if (rate.signum() > 0) {
				tax = net.multiply(rate).divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
				obj.setTaxRate(rate);
				obj.setTaxAmount(tax);
			}
		}
		java.math.BigDecimal bill = net.add(tax);   // vendor bill = goods + input tax
		java.math.BigDecimal paid = obj.getPaidAmount() != null ? obj.getPaidAmount() : bill;
		obj.setPaidAmount(paid);
		obj.setDueAmount(paid.subtract(bill));
		Purchase saved = this.save(obj);
		if (saved.getVenderId() != null) venderService.recomputePayable(saved.getVenderId());   // F1 (AP)
		pushPurchaseToInventory(saved, dto, user);        // dual-write stock-in to inventory (authoritative)

		// Option B — re-price on receive: the purchase's sell rate updates the catalog Product's selling price
		// (the master POS/pharmacy/e-commerce all sell at). GUARD: only a positive rate re-prices; a blank/0 leaves
		// the master price untouched. Best-effort — a catalog hiccup never fails the purchase (stock is already in).
		if (snap != null && snap.getBsellRate() != null
				&& snap.getBsellRate().compareTo(java.math.BigDecimal.ZERO) > 0 && saved.getProductId() != null) {
			try { catalogClient.updatePrice(saved.getProductId(), snap.getBsellRate()); }
			catch (Exception ex) { LOG.warn("Option B: re-price on receive failed for product {} (purchase recorded)", saved.getProductId(), ex); }
		}

		// F3b: auto-post the purchase to the GL (Dr Inventory + Dr TAX(input), Cr Cash(paid)/AP(rest)). Best-effort.
		try {
			glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
					.eventType("PURCHASE").date(java.time.LocalDate.now()).ref(saved.getPurchaseInvoiceNo())
					.grandTotal(nz(saved.getTotalAmount()).add(nz(saved.getTaxAmount())))   // bill = net + input tax
					.taxTotal(nz(saved.getTaxAmount())).paidAmount(saved.getPaidAmount()).method("CASH").build());
		} catch (Exception ex) {
			LOG.warn("GL enqueue failed for purchase {} (recorded)", saved.getPurchaseInvoiceNo(), ex);
		}

		// Audit #5: record this purchase (atomic with the write) so a repeat with the same key replays the same bill.
		idempotencyService.record(org, "addPurchase", idemKey, String.valueOf(saved.getPurchaseId()));
		// Audit #6: append-only trail.
		auditService.record("PURCHASE", "BILL", saved.getPurchaseInvoiceNo(), saved.getTotalAmount(), null);
		return saved;
	}

	@Override
	@Transactional
	public Purchase updatePurchase(PurchaseDTO dto) throws Exception {
		AuthenticatedUser user = requestUtil.getCurrentUser();
		if (dto.getPurchaseId() == null) throw new RuntimeException("updatePurchase requires a purchaseId");

		// Anti-IDOR: the edited purchase must belong to the caller's tenant.
		Purchase existing = purchaseRepo.findById(dto.getPurchaseId())
				.filter(p -> scopeMatches(p, user))
				.orElseThrow(() -> new RuntimeException("Purchase not found: " + dto.getPurchaseId()));
		if ("VOID".equals(existing.getStatus()))   // Audit #3: a voided bill is read-only
			throw new RuntimeException("This bill is voided and cannot be edited.");
		// Period close: an edit rewrites the ORIGINAL bill in place, so its period must still be open.
		periodLockGuard.assertOpen(existing.getDated() != null ? existing.getDated().toLocalDate() : java.time.LocalDate.now());

		float oldQty = existing.getQuantity() != null ? existing.getQuantity() : 0f;
		Long oldProductId = existing.getProductId();
		String oldBatchNo = existing.getBatchNo();   // reconcile against the batch that was originally imported
		java.math.BigDecimal oldBillTotal = nz(existing.getTotalAmount());   // GL: reverse the OLD posting on edit (net)
		java.math.BigDecimal oldTax = nz(existing.getTaxAmount());          // old input tax (reversed on edit)
		java.math.BigDecimal oldBillPaid = nz(existing.getPaidAmount());

		// Update the record (keep id + original audit/tenant; product is readonly on edit).
		dto.setUserId(user.getUserId());
		modelMapper.addConverter(appUtil.stringToLocalDateTimeIgnoreEmptyOrNull);
		modelMapper.addConverter(appUtil.stringToLocalDateIgnoreEmptyOrNull);
		Purchase obj = modelMapper.map(dto, Purchase.class);
		obj.setPurchaseId(existing.getPurchaseId());
		obj.setDated(existing.getDated() != null ? existing.getDated() : LocalDateTime.now());
		obj.setUpdated(LocalDateTime.now());
		obj.setUserId(existing.getUserId());
		obj.setOrganizationId(existing.getOrganizationId());
		obj.setStoreId(existing.getStoreId());            // preserve the store on edit
		obj.setProductId(dto.getProductId() != null ? dto.getProductId() : oldProductId);
		StockDTO snap = dto.getStock();
		if (snap != null) {
			obj.setBatchNo(snap.getBatchNo() != null ? snap.getBatchNo() : oldBatchNo);
			obj.setBpurchaseRate(snap.getBpurchaseRate());
			obj.setBsellRate(snap.getBsellRate());
			obj.setBpurchaseDiscount(snap.getBpurchaseDiscount());
			obj.setBsellDiscount(snap.getBsellDiscount());
			obj.setBpurchaseDiscountType(snap.getBpurchaseDiscountType());
			obj.setBsellDiscountType(snap.getBsellDiscountType());
			obj.setBexpDate(appUtil.toLocalDateOrNull(snap.getBexpDate()));
		}
		// F1 (AP): recompute the bill's payment position on edit — bill = totalAmount (what we owe; netAmount is
		// profit). Keep the prior paid unless the edit supplies a new one; dueAmount = paid − bill. venderId may
		// change on edit, so refresh BOTH the old and new vendor payables.
		// Phase B: recompute input tax on edit (net = totalAmount; the vendor bill = net + tax) when the org's
		// "Purchase tax" toggle is on. Off (or 0 rate) → tax 0 → bill = net (unchanged behaviour).
		java.math.BigDecimal newNet = nz(obj.getTotalAmount());
		java.math.BigDecimal newTax = java.math.BigDecimal.ZERO;
		var taxSetting = taxService.settingsFor(existing.getOrganizationId());
		if (taxSetting != null && Boolean.TRUE.equals(taxSetting.getInputTaxEnabled())) {
			java.math.BigDecimal rate = TaxService.resolveRate(dto.getTaxRate(), taxSetting);
			if (rate.signum() > 0) {
				newTax = newNet.multiply(rate).divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
				obj.setTaxRate(rate);
			}
		}
		obj.setTaxAmount(newTax.signum() > 0 ? newTax : java.math.BigDecimal.ZERO);
		if (newTax.signum() <= 0) obj.setTaxRate(null);
		java.math.BigDecimal bill = newNet.add(newTax);   // gross vendor bill
		java.math.BigDecimal paid = obj.getPaidAmount() != null ? obj.getPaidAmount()
				: (existing.getPaidAmount() != null ? existing.getPaidAmount() : bill);
		obj.setPaidAmount(paid);
		obj.setDueAmount(paid.subtract(bill));
		Purchase saved = this.save(obj);
		Long oldVendor = existing.getVenderId();
		if (oldVendor != null) venderService.recomputePayable(oldVendor);
		if (saved.getVenderId() != null && !saved.getVenderId().equals(oldVendor))
			venderService.recomputePayable(saved.getVenderId());

		// Reconcile inventory by the quantity DELTA (new − old) against the purchase's own batch — NOT a re-import.
		// Runs inside this @Transactional: a guard rejection (e.g. reducing below stock already sold) throws and
		// rolls the record edit back too, so the record and inventory never diverge.
		float newQty = saved.getQuantity() != null ? saved.getQuantity() : 0f;
		float delta = newQty - oldQty;
		if (tradeSagaProperties.isEnabled() && saved.getProductId() != null && delta != 0f) {
			inventoryClient.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
					.productId(saved.getProductId())
					.batchNo(oldBatchNo)
					.delta(delta)
					.expiryDate(saved.getBexpDate())
					.purchasePrice(saved.getBpurchaseRate())
					.build());
		}

		// Option B — an edited sell rate re-prices the catalog master too (guarded, best-effort).
		if (snap != null && snap.getBsellRate() != null
				&& snap.getBsellRate().compareTo(java.math.BigDecimal.ZERO) > 0 && saved.getProductId() != null) {
			try { catalogClient.updatePrice(saved.getProductId(), snap.getBsellRate()); }
			catch (Exception ex) { LOG.warn("Option B: re-price on edit failed for product {} (purchase updated)", saved.getProductId(), ex); }
		}

		// GL edit adjustment: reverse the OLD bill + repost the NEW (net = the edit's delta) so the books never
		// drift on a purchase edit. Best-effort — never fail the edit.
		try {
			java.math.BigDecimal oldGross = oldBillTotal.add(oldTax);
			if (oldGross.signum() > 0)
				glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
						.eventType("PURCHASE_RETURN").date(java.time.LocalDate.now()).ref(saved.getPurchaseInvoiceNo())
						.grandTotal(oldGross).taxTotal(oldTax).paidAmount(oldBillPaid).method("CASH").build());
			glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
					.eventType("PURCHASE").date(java.time.LocalDate.now()).ref(saved.getPurchaseInvoiceNo())
					.grandTotal(nz(saved.getTotalAmount()).add(nz(saved.getTaxAmount())))
					.taxTotal(nz(saved.getTaxAmount())).paidAmount(saved.getPaidAmount()).method("CASH").build());
		} catch (Exception ex) {
			LOG.warn("GL adjustment enqueue failed for purchase edit {} (edit applied)", saved.getPurchaseInvoiceNo(), ex);
		}
		auditService.record("PURCHASE_EDIT", "BILL", saved.getPurchaseInvoiceNo(), saved.getTotalAmount(), null);   // #6
		return saved;
	}

	/** Anti-IDOR scope check for a purchase the caller named by id: their org owns it (or it is a legacy org-NULL
	 *  row of theirs), AND (P2c) it sits in a store they can access — an admin at Store B must not edit, return or
	 *  void a Store-A bill just by knowing its id. The store rule is a no-op for single-store/ungranted callers. */
	private boolean scopeMatches(Purchase p, AuthenticatedUser user) {
		if (!requestUtil.canAccessStore(p.getStoreId())) return false;
		Long orgId = user.getOrganizationId();
		if (orgId != null && orgId.equals(p.getOrganizationId())) return true;
		return p.getOrganizationId() == null && user.getUserId() != null && user.getUserId().equals(p.getUserId());
	}

	private static java.math.BigDecimal nz(java.math.BigDecimal v) { return v != null ? v : java.math.BigDecimal.ZERO; }

	/**
	 * Purchase Return (debit note): return goods to the vendor. Reverses the stock-in, reconciles the bill's
	 * payment position (mirror of the sale-return SF-5 reconcile) so the vendor refunds any overpayment, refreshes
	 * the vendor payable, and posts a PURCHASE_RETURN reversal journal to the GL. Partial or full. All-or-nothing
	 * (@Transactional): if inventory rejects the stock-out (e.g. those goods were already sold on), the whole
	 * return rolls back so the record + inventory + books never diverge.
	 */
	@Override
	@Transactional
	public java.util.Map<String, Object> purchaseReturn(Long purchaseId, Float returnQty, String reason) {
		AuthenticatedUser user = requestUtil.getCurrentUser();
		Purchase p = purchaseRepo.findById(purchaseId).filter(x -> scopeMatches(x, user))
				.orElseThrow(() -> new RuntimeException("Purchase not found: " + purchaseId));
		if ("VOID".equals(p.getStatus()))   // Audit #3: no returns against a voided bill
			throw new RuntimeException("This bill is voided.");
		float soldQty = p.getQuantity() != null ? p.getQuantity() : 0f;
		float rq = returnQty != null ? returnQty : 0f;
		if (rq <= 0f) throw new RuntimeException("Return quantity must be greater than 0.");
		if (rq > soldQty) throw new RuntimeException("Cannot return more than was purchased (" + soldQty + ").");
		// Period close: a purchase return posts a new debit note dated today, so the CURRENT period must be open.
		periodLockGuard.assertOpen(java.time.LocalDate.now());
		boolean partial = rq < soldQty;

		// Phase B: reverse on the GROSS bill (goods + input tax). Both are returned proportionally on a partial return.
		java.math.BigDecimal net = nz(p.getTotalAmount());   // goods value
		java.math.BigDecimal tax = nz(p.getTaxAmount());     // input tax on the bill (0 unless the org captures it)
		java.math.BigDecimal frac = partial
				? java.math.BigDecimal.valueOf(rq).divide(java.math.BigDecimal.valueOf(soldQty), 6, java.math.RoundingMode.HALF_UP)
				: java.math.BigDecimal.ONE;
		java.math.BigDecimal returnedNet = partial ? net.multiply(frac).setScale(2, java.math.RoundingMode.HALF_UP) : net;
		java.math.BigDecimal returnedTax = partial ? tax.multiply(frac).setScale(2, java.math.RoundingMode.HALF_UP) : tax;
		java.math.BigDecimal returnedGross = returnedNet.add(returnedTax);

		// 1) reverse the stock-in for this batch (negative delta). A guard rejection rolls the whole return back.
		if (tradeSagaProperties.isEnabled() && p.getProductId() != null) {
			inventoryClient.reconcilePurchase(com.myplus.commerce.contracts.dto.StockPurchaseAdjust.builder()
					.productId(p.getProductId()).batchNo(p.getBatchNo()).delta(-rq)
					.expiryDate(p.getBexpDate()).purchasePrice(p.getBpurchaseRate()).build());
		}

		// 2) reconcile the bill (mirror SF-5 for AP) on the GROSS bill: reduce it by the returned gross; the vendor
		//    refunds any resulting overpayment; dueAmount = paid − remaining gross.
		java.math.BigDecimal grossTotal = net.add(tax);
		java.math.BigDecimal newGross = grossTotal.subtract(returnedGross);
		java.math.BigDecimal priorPaid = nz(p.getPaidAmount());
		java.math.BigDecimal refund = priorPaid.subtract(newGross).max(java.math.BigDecimal.ZERO);   // vendor refunds overpayment
		java.math.BigDecimal newPaid = priorPaid.subtract(refund);
		if (partial) {
			java.math.BigDecimal keepFrac = java.math.BigDecimal.valueOf(soldQty - rq)
					.divide(java.math.BigDecimal.valueOf(soldQty), 6, java.math.RoundingMode.HALF_UP);
			p.setQuantity(soldQty - rq);
			p.setTotalAmount(net.subtract(returnedNet));
			p.setTaxAmount(tax.subtract(returnedTax));
			p.setNetAmount(nz(p.getNetAmount()).multiply(keepFrac).setScale(2, java.math.RoundingMode.HALF_UP));
		} else {
			p.setQuantity(0f);
			p.setTotalAmount(java.math.BigDecimal.ZERO);
			p.setTaxAmount(java.math.BigDecimal.ZERO);
			p.setNetAmount(java.math.BigDecimal.ZERO);
		}
		p.setPaidAmount(newPaid);
		p.setDueAmount(newPaid.subtract(nz(p.getTotalAmount()).add(nz(p.getTaxAmount()))));   // paid − remaining gross
		p.setUpdated(LocalDateTime.now());
		purchaseRepo.save(p);
		if (p.getVenderId() != null) venderService.recomputePayable(p.getVenderId());

		// 3) GL reversal (best-effort): Cr Inventory(returned net) + Cr TAX(returned input tax), Dr AP + Dr Cash(refund).
		try {
			glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
					.eventType("PURCHASE_RETURN").date(java.time.LocalDate.now()).ref(p.getPurchaseInvoiceNo())
					.grandTotal(returnedGross).taxTotal(returnedTax).paidAmount(refund).method("CASH").build());
		} catch (Exception ex) {
			LOG.warn("GL enqueue failed for purchase return {} (return applied)", p.getPurchaseInvoiceNo(), ex);
		}

		auditService.record("PURCHASE_RETURN", "BILL", p.getPurchaseInvoiceNo(), returnedGross, "qty=" + rq);   // #6

		java.util.Map<String, Object> out = new java.util.HashMap<>();
		out.put("success", true);
		out.put("returnedValue", returnedGross);
		out.put("refund", refund);
		out.put("newDue", p.getDueAmount());
		return out;
	}

	/**
	 * Audit #3: VOID a bill — the books-safe replacement for hard-delete. Reverses the full remaining quantity through
	 * {@link #purchaseReturn} (stock-out + AP reconcile + GL PURCHASE_RETURN), then soft-stamps the row VOID (record +
	 * history survive) and read-only. Runs in one @Transactional: if the stock reversal is rejected, nothing changes.
	 */
	@Override
	@Transactional
	public java.util.Map<String, Object> voidBill(Long purchaseId, String reason) {
		AuthenticatedUser user = requestUtil.getCurrentUser();
		Purchase p = purchaseRepo.findById(purchaseId).filter(x -> scopeMatches(x, user))
				.orElseThrow(() -> new RuntimeException("Purchase not found: " + purchaseId));
		if ("VOID".equals(p.getStatus()))
			throw new RuntimeException("This bill is already voided.");
		// Period close: a void reverses the ORIGINAL bill in place, so its period must still be open.
		periodLockGuard.assertOpen(p.getDated() != null ? p.getDated().toLocalDate() : java.time.LocalDate.now());
		float qty = p.getQuantity() != null ? p.getQuantity() : 0f;
		if (qty <= 0f)
			throw new RuntimeException("Nothing to void on this bill.");

		// Reuse the full reversal (stock + AP + GL) for the whole remaining quantity, then stamp VOID.
		java.util.Map<String, Object> out = purchaseReturn(purchaseId, qty, reason);
		Purchase voided = purchaseRepo.findById(purchaseId).orElse(p);
		voided.setStatus("VOID");
		voided.setVoidedBy(user != null ? user.getUserId() : null);
		voided.setVoidedAt(LocalDateTime.now());
		voided.setVoidReason(reason);
		voided.setUpdated(LocalDateTime.now());
		purchaseRepo.save(voided);
		auditService.record("VOID_PURCHASE", "BILL", voided.getPurchaseInvoiceNo(),
				(java.math.BigDecimal) out.get("returnedValue"), reason);   // #6
		return out;
	}

	/**
	 * D3 (slice 33) + M3.2 (slice 63): when the saga is enabled, push the purchased quantity into inventory so
	 * inventory is authoritative for stock. The item is auto-mapped to a catalog product on demand
	 * ({@code ensureMapped}) — so EVERY purchase reaches inventory, including legacy items never bulk-migrated.
	 * Best-effort: a failure (catalog/inventory down) never fails the purchase (recorded locally; reconcile later).
	 */
	void pushPurchaseToInventory(Purchase obj, PurchaseDTO dto, AuthenticatedUser user) {
		if (!tradeSagaProperties.isEnabled() || dto.getQuantity() == null || dto.getQuantity() <= 0) {
			return;
		}
		try {
			Long productId = obj.getProductId();          // M3b: mapped once in addPurchase
			if (productId == null) return;
			inventoryClient.importStock(List.of(
					com.myplus.commerce.contracts.dto.StockImportLine.builder()
							.productId(productId)
							.quantity(dto.getQuantity())
							.batchNo(obj.getBatchNo())
							.expiryDate(obj.getBexpDate())
							.purchasePrice(obj.getBpurchaseRate())
							.costPrice(obj.getBpurchaseRate())
							.build()));
		} catch (Exception ex) {
			LOG.warn("M3b: inventory stock-in failed for product {} (purchase recorded locally; reconcile later)",
					dto.getProductId(), ex);
		}
	}

	public void deleteAllByIdInBatch(Iterable<Long> ids) {
		purchaseRepo.deleteAllByIdInBatch(ids);
	}

	public void deleteAllInBatch(Iterable<Purchase> entities) {
		purchaseRepo.deleteAllInBatch(entities);
	}

	public Purchase getById(Long id) {
		return purchaseRepo.getById(id);
	}

	public Purchase getReferenceById(Long id) {
		return purchaseRepo.getReferenceById(id);
	}

	public <S extends Purchase> List<S> saveAllAndFlush(Iterable<S> entities) {
		return purchaseRepo.saveAllAndFlush(entities);
	}

	public void deleteAllById(Iterable<? extends Long> ids) {
		purchaseRepo.deleteAllById(ids);
	}

	public <S extends Purchase, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
		return purchaseRepo.findBy(example, queryFunction);
	}

}