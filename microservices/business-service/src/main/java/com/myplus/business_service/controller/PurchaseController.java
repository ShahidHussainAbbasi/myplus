package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.service.ICompanyService;
// import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IPurchaseService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.ReturnDocumentDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@Controller
public class PurchaseController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IPurchaseService purchaseService;
	
	@Autowired
	ICompanyService companyService;

	// @Autowired
	// IItemTypeService itemTypeService;

	@Autowired
	IItemUnitService itemUnitService;

	@Autowired
	IVenderService venderService;

	@Autowired
	com.myplus.commerce.contracts.client.CatalogClient catalogClient;   // M4d: resolve line names from catalog

	@Autowired
	com.myplus.business_service.repository.PurchaseReturnRepo purchaseReturnRepo;   // task #15: debit-note document

	/** M4d (slice 96): batch-resolve catalog ProductRef by productId for the read grid (name/sku); best-effort. */
	private java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productRefs(java.util.List<Long> productIds) {
		if (productIds == null || productIds.isEmpty()) return java.util.Collections.emptyMap();
		try {
			return catalogClient.getProducts(productIds).stream()
				.collect(java.util.stream.Collectors.toMap(com.myplus.commerce.contracts.dto.ProductRef::getId, p -> p, (a, b) -> a));
		} catch (Exception e) {
			LOGGER.warn("M4d: catalog getProducts failed for {} id(s); purchase line names may be blank", productIds.size(), e);
			return java.util.Collections.emptyMap();
		}
	}

	@Autowired
	RequestUtil requestUtil;

    @Autowired
    private AppUtil appUtil;  
    
	ModelMapper modelMapper = new ModelMapper();

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}
	/** Role-aware visibility: owner/super AND admin see the whole org's purchases; a plain user only their own. */
	private boolean seesAllOrg() {
		return requestUtil.callerSeesWholeOrg();
	}
	private List<Purchase> visiblePurchases() {
		if (requestUtil.isOwnerSuper())                       // owner: whole org, all stores, always
			return purchaseService.findScoped(orgId(), userId());
		java.util.Set<Long> stores = requestUtil.accessibleStoreIds();
		if (stores.isEmpty())
			return seesAllOrg() ? purchaseService.findScoped(orgId(), userId())
			                    : purchaseService.findOwnScoped(orgId(), userId());
		return seesAllOrg() ? purchaseService.findScopedByStores(orgId(), stores)
		                    : purchaseService.findOwnScopedByStores(orgId(), userId(), stores);
	}

	@RequestMapping(value = "/getUserPurchase", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserPurchase(final HttpServletRequest request) {
		try {
			List<Purchase> objs = visiblePurchases();   // role-aware: SUPER = org, others = own
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			// M4d (slice 96): batch-resolve line names from catalog ProductRef (no Item entity load). The purchase
			// carries its own productId, so no reverse map is needed.
			java.util.List<Long> pProductIds = objs.stream().filter(o -> o.getProductId() != null)
					.map(com.myplus.business_service.entity.Purchase::getProductId).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(pProductIds);

			// Vendor name for the grid + edit-select — mirrors getUserVender resolving companyName from the relation.
			// The purchase stores venderId (not a relation), so batch-resolve id → name once.
			java.util.List<Long> pVenderIds = objs.stream().filter(o -> o.getVenderId() != null)
					.map(com.myplus.business_service.entity.Purchase::getVenderId).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, String> venderNameById = new java.util.HashMap<>();
			if (!pVenderIds.isEmpty())
				venderService.findAllById(pVenderIds).forEach(v -> venderNameById.put(v.getId(), v.getName()));

			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>();
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				PurchaseDTO dto = modelMapper.map(o, PurchaseDTO.class);

				// M4e.d (slice 106): identity from the purchase's own productId; name/sku from catalog ProductRef (no Item load).
				if (o.getProductId() == null) return;   // truly unidentifiable line
				com.myplus.commerce.contracts.dto.ProductRef p = productById.get(o.getProductId());
				if (p != null) {
					dto.setIname(p.getName());
					dto.setIcode(p.getSku());
				}

				StockDTO sd = new StockDTO();   // the UI grid's nested batch/rate contract, built from the purchase
				sd.setBatchNo(o.getBatchNo());
				sd.setBpurchaseRate(o.getBpurchaseRate());
				sd.setBsellRate(o.getBsellRate());
				sd.setBpurchaseDiscount(o.getBpurchaseDiscount());
				sd.setBsellDiscount(o.getBsellDiscount());
				sd.setBpurchaseDiscountType(o.getBpurchaseDiscountType());
				sd.setBsellDiscountType(o.getBsellDiscountType());
				sd.setBexpDate(o.getBexpDate() != null ? o.getBexpDate().toString() : null);
				sd.setStock(o.getQuantity());                 // the purchased quantity
				dto.setStock(sd);
				dto.setVenderName(venderNameById.get(o.getVenderId()));   // grid + edit: show/preselect the vendor
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserPurchase "+e.getCause(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getAllPurchase", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllPurchase(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Purchase> objs = visiblePurchases();   // role-aware: SUPER = org, others = own
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>();
			objs.forEach(obj ->{
				PurchaseDTO dto = modelMapper.map(obj, PurchaseDTO.class);
//				dto.setItemUnitId(obj.getItemUnit().getId());
//				dto.setItemUnitName(obj.getItemUnit().getName());
//				dto.setItemTypeId(obj.getItemType().getId());
//				dto.setItemTypeName(obj.getItemType().getName());
//				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
//				dto.setUpdated(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getAllPurchase "+e.getCause(), e);			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addPurchase", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addPurchase(@Validated final PurchaseDTO dto, final HttpServletRequest request) {
		try {
			if(appUtil.isEmptyOrNull(purchaseService.addPurchase(dto))) {
				if(appUtil.isEmptyOrNull(dto.getPurchaseId())) {
					return new GenericResponse("FAILED", "Failed to save purchase. Please try again.");
				}else {
					return new GenericResponse("FAILED", "Failed to update purchase. Please try again.");
				}
			}else {
				if(appUtil.isEmptyOrNull(dto.getPurchaseId())) {
					return new GenericResponse("SUCCESS", "Purchase saved successfully.");
				}else {
					return new GenericResponse("SUCCESS", "Purchase updated successfully.");
				}
			}
		} catch (com.myplus.business_service.service.PeriodClosedException pce) {
			LOGGER.warn("addPurchase rejected (period closed): {}", pce.getMessage());
			return new GenericResponse("FAILED", pce.getMessage());
		} catch (com.myplus.business_service.service.CreditConfirmationRequiredException confirm) {
			// B2B-P1 (#9, supplier side): over the vendor's credit limit under policy=warn, not yet
			// acknowledged. Nothing was written and no stock came in. CONFIRM, not ERROR — nothing failed.
			LOGGER.info("addPurchase awaiting credit-limit confirmation: {}", confirm.getMessage());
			return new GenericResponse("CONFIRM", confirm.getMessage());
		} catch (com.myplus.common.web.exception.ValidationException blocked) {
			// policy=block — surface the reason verbatim instead of the generic handler's "unexpected error".
			LOGGER.warn("addPurchase rejected (credit limit): {}", blocked.getMessage());
			return new GenericResponse("ERROR", blocked.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addPurchase "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}

	/** Edit an existing purchase: update the record AND reconcile inventory by the quantity delta (new − old)
	 *  against the purchase's own batch — no re-import. A guard rejection (e.g. reducing below stock already sold)
	 *  rolls the whole edit back and its message is surfaced to the user. */
	@RequestMapping(value = "/updatePurchase", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse updatePurchase(@Validated final PurchaseDTO dto, final HttpServletRequest request) {
		try {
			if (appUtil.isEmptyOrNull(dto.getPurchaseId())) {
				return new GenericResponse("ERROR", "Missing purchase id for update.");
			}
			if (appUtil.isEmptyOrNull(purchaseService.updatePurchase(dto))) {
				return new GenericResponse("FAILED", "Failed to update purchase. Please try again.");
			}
			return new GenericResponse("SUCCESS", "Purchase updated successfully.");
		} catch (com.myplus.business_service.service.BusinessRuleException | com.myplus.business_service.service.PeriodClosedException rule) {
			// Expected user-facing rejection (voided bill, closed period, etc.) — surface the reason, no ERROR/stack trace.
			LOGGER.warn("updatePurchase rejected: {}", rule.getMessage());
			return new GenericResponse("FAILED", rule.getMessage());
		} catch (Exception e) {
			// A remote inventory rejection (e.g. reducing a bill below stock already sold) surfaces its message; a
			// genuine fault falls back to the generic text.
			String msg = e.getMessage();
			boolean reduceRejection = msg != null && msg.toLowerCase().contains("cannot reduce");
			if (reduceRejection) {
				LOGGER.warn("updatePurchase rejected: {}", msg);
				return new GenericResponse("FAILED", msg);
			}
			LOGGER.error(this.getClass().getName()+" > updatePurchase "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	/** Purchase Return (debit note): reverse stock-in + reconcile the vendor bill/payable + post a GL reversal. */
	@RequestMapping(value = "/purchaseReturn", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse purchaseReturn(final HttpServletRequest request) {
		try {
			String pid = request.getParameter("purchaseId");
			String qty = request.getParameter("quantity");
			if (appUtil.isEmptyOrNull(pid) || appUtil.isEmptyOrNull(qty))
				return new GenericResponse("FAILED", "Purchase and quantity are required.");
			java.util.Map<String, Object> result = purchaseService.purchaseReturn(
					Long.valueOf(pid.trim()), Float.valueOf(qty.trim()), request.getParameter("reason"));
			// B2B-P3c (#1): name the debit note so the operator can quote it to the supplier.
			Object dbn = result != null ? result.get("debitNoteNo") : null;
			return new GenericResponse("SUCCESS",
					dbn != null ? ("Purchase returned. Debit note " + dbn) : "Purchase returned successfully.",
					result);
		} catch (NumberFormatException nfe) {
			return new GenericResponse("FAILED", "Invalid purchase id or quantity.");
		} catch (com.myplus.business_service.service.BusinessRuleException | com.myplus.business_service.service.PeriodClosedException rule) {
			// Expected user-facing rejection (e.g. voided bill, over-return) — surface the reason, no ERROR/stack trace.
			LOGGER.warn("purchaseReturn rejected: {}", rule.getMessage());
			return new GenericResponse("FAILED", rule.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > purchaseReturn " + e.getCause(), e);
			return new GenericResponse("FAILED", e.getMessage() != null ? e.getMessage() : "An unexpected error occurred. Please contact support.");
		}
	}

	/**
	 * Task #15/#21 — one {@code PurchaseReturn} row, resolved into the debit-note shape.
	 *
	 * <p>Shared by the single-note read and the register so the two can never disagree. Resolved values are
	 * passed IN because the register batches them for a whole page; a helper that fetched its own would turn
	 * that into an N+1.
	 *
	 * <p>{@code rate} is null from the register: it lives on the original bill, and loading every reversed
	 * bill to fill a column the list does not show would be work for nothing. The single-note read passes it.
	 */
	private ReturnDocumentDTO toDebitNoteDto(com.myplus.business_service.entity.PurchaseReturn r,
			com.myplus.commerce.contracts.dto.ProductRef p, String venderName, java.math.BigDecimal rate) {
		return ReturnDocumentDTO.builder()
				.documentType("DEBIT_NOTE")
				.documentNo(r.getDebitNoteNo())
				.referenceNo(r.getPurchaseInvoiceNo())
				.dated(r.getDated() != null ? r.getDated().toLocalDate().toString() : null)
				.partyName(venderName)
				.reason(r.getReason())
				.totalAmount(r.getAmount())
				.storeId(r.getStoreId())
				.lines(java.util.List.of(ReturnDocumentDTO.Line.builder()
						.productId(r.getProductId())
						.productName(p != null ? p.getName() : null)
						.sku(p != null ? p.getSku() : null)
						.quantity(r.getQuantity())
						.rate(rate)
						.amount(r.getAmount())
						.build()))
				.build();
	}

	/**
	 * Task #21 — the debit-note register for this tenant, newest first.
	 *
	 * <p>The mirror of {@code /getSaleReturns}, which has existed since SF-11 while the purchase side had no
	 * list at all — the asymmetry that left a debit note unreachable the moment its print prompt was dismissed.
	 *
	 * <p>Flat rows, no lazy relations, org-scoped. No {@code userId} fallback, unlike the sale side, and that
	 * is correct rather than an omission: V33 CREATED {@code purchase_return}, so no pre-migration org-NULL
	 * rows exist for a fallback to rescue.
	 */
	@RequestMapping(value = "/getPurchaseReturns", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getPurchaseReturns(final HttpServletRequest request) {
		try {
			/*
			 * Returned as the SAME ReturnDocumentDTO the printable note uses — see the sale side for why a
			 * register of raw rows would be a table of ids. Names resolved in TWO batched lookups for the
			 * whole page, never per row.
			 */
			List<com.myplus.business_service.entity.PurchaseReturn> rows = purchaseReturnRepo.findScoped(orgId());

			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(
					rows.stream().map(com.myplus.business_service.entity.PurchaseReturn::getProductId)
							.filter(java.util.Objects::nonNull).distinct()
							.collect(java.util.stream.Collectors.toList()));

			java.util.Map<Long, String> venderNameById = new java.util.HashMap<>();
			List<Long> venderIds = rows.stream()
					.map(com.myplus.business_service.entity.PurchaseReturn::getVenderId)
					.filter(java.util.Objects::nonNull).distinct()
					.collect(java.util.stream.Collectors.toList());
			if (!venderIds.isEmpty())
				venderService.findAllById(venderIds).forEach(v -> venderNameById.put(v.getId(), v.getName()));

			return new GenericResponse("SUCCESS", "Purchase returns loaded", rows.stream()
					.map(r -> toDebitNoteDto(r, productById.get(r.getProductId()),
							venderNameById.get(r.getVenderId()), null))
					.collect(java.util.stream.Collectors.toList()));
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getPurchaseReturns " + e.getMessage(), e);
			return new GenericResponse("FAILED", "Could not load purchase returns.");
		}
	}

	/**
	 * Task #15 — ONE debit note, assembled into something a document can draw.
	 *
	 * <p>The mirror of {@code /creditNote} on the sale side, and it exists for the same reason: a
	 * {@code PurchaseReturn} row holds a {@code productId} and a {@code venderId} but neither name, so it
	 * cannot draw itself. A debit note is what a supplier reconciles your return against — the number is
	 * already allocated ({@code debitNoteNo}, serialised per org), only the document was missing.
	 *
	 * <p><b>Keyed on the note NUMBER</b> — the document's identity, and what the supplier reconciles against.
	 * <b>Anti-IDOR is the scope predicate inside the query</b>, not the key: a row id would be no harder to
	 * guess than {@code DBN-000012}. The store rule is re-applied per record through the shared
	 * {@code requestUtil.canAccessStore(...)} because the list queries filter by store while this endpoint
	 * takes its key from the client — without it an admin at Store B could read a Store-A debit note.
	 *
	 * <p>Unlike the sale side there is no unrecoverable-value case to guard: {@code PurchaseReturn.amount} has
	 * been written since the table was created in V33, so a debit note always knows what it is worth.
	 */
	@RequestMapping(value = "/debitNote", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse debitNote(@RequestParam(name = "no") final String noteNo,
			final HttpServletRequest request) {
		try {
			if (appUtil.isEmptyOrNull(noteNo)) return new GenericResponse("NOT_FOUND");

			com.myplus.business_service.entity.PurchaseReturn r =
					purchaseReturnRepo.findByDebitNoteNoScoped(noteNo.trim(), orgId()).orElse(null);
			// One combined not-found for "absent" and "not yours" — distinguishing them would tell a prober
			// which notes exist in other tenants, which is what the scoped query is closing.
			if (r == null || !requestUtil.canAccessStore(r.getStoreId()))
				return new GenericResponse("NOT_FOUND");

			String venderName = r.getVenderId() != null
					? venderService.findById(r.getVenderId())
							.map(com.myplus.business_service.entity.Vender::getName).orElse(null)
					: null;

			com.myplus.commerce.contracts.dto.ProductRef p = r.getProductId() != null
					? productRefs(java.util.List.of(r.getProductId())).get(r.getProductId()) : null;

			// The rate the goods were bought at, from the bill this return reverses.
			Purchase bought = r.getPurchaseId() != null
					? purchaseService.findById(r.getPurchaseId()).orElse(null) : null;

			return new GenericResponse("SUCCESS", "Debit note loaded",
					toDebitNoteDto(r, p, venderName, bought != null ? bought.getBpurchaseRate() : null));
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > debitNote " + e.getMessage(), e);
			return new GenericResponse("FAILED", "Could not load the debit note.");
		}
	}

	/**
	 * Audit #3: VOID a bill — reverses stock-in + vendor payable + posts a GL PURCHASE_RETURN, then soft-stamps the
	 * bill VOID (record + audit survive). Books-safe replacement for hard-delete.
	 */
	@PreAuthorize("hasAuthority('VOID_INVOICE')")
	@RequestMapping(value = "/voidPurchase", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse voidPurchase(final HttpServletRequest request) {
		try {
			String pid = request.getParameter("purchaseId");
			if (appUtil.isEmptyOrNull(pid))
				return new GenericResponse("FAILED", "Purchase id is required.");
			java.util.Map<String, Object> result = purchaseService.voidBill(Long.valueOf(pid.trim()), request.getParameter("reason"));
			return new GenericResponse("SUCCESS", "Bill voided.", result);
		} catch (NumberFormatException nfe) {
			return new GenericResponse("FAILED", "Invalid purchase id.");
		} catch (com.myplus.business_service.service.BusinessRuleException | com.myplus.business_service.service.PeriodClosedException rule) {
			// Expected user-facing rejection (e.g. already voided) — surface the reason, no scary ERROR/stack trace.
			LOGGER.warn("voidPurchase rejected: {}", rule.getMessage());
			return new GenericResponse("FAILED", rule.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > voidPurchase " + e.getCause(), e);
			return new GenericResponse("FAILED", e.getMessage() != null ? e.getMessage() : "An unexpected error occurred. Please contact support.");
		}
	}

	/**
	 * Audit #3: hard-delete RETIRED — bypassed stock/AP/GL reversal + audit. Cancellations go through
	 * {@code /voidPurchase}. Stub kept so the route can't 404; performs no deletion.
	 */
	@PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
	@RequestMapping(value = "/deletePurchase", method = RequestMethod.POST)
	@ResponseBody
	public boolean deletePurchase( HttpServletRequest req, HttpServletResponse resp ){
		LOGGER.warn("deletePurchase is retired; use /voidPurchase (books-safe void). No rows deleted.");
		return false;
	}
}
