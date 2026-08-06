package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.Company;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.service.CustomerService;
import com.myplus.business_service.service.ICustomerHistoryService;
import com.myplus.business_service.service.ICustomerService;
// import com.myplus.business_service.service.ICustomerService;
// import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IPurchaseService;
import com.myplus.business_service.service.ISellService;
import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.ObjectMapperUtils;
import com.myplus.business_service.util.RequestUtil;

@RestController
public class SellController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	
	@Autowired
	private MessageSource messages;

	@Autowired
	ISellService sellService;

	@Autowired
	com.myplus.business_service.repository.SellBatchRepo sellBatchRepo;   // B2B-P3b-2 (#4): receipt traceability
	
	@Autowired
	ICustomerService customerService;

	// @Autowired
	// IItemTypeService itemTypeService;

	@Autowired
	IItemUnitService itemUnitService;


	@Autowired
	IPurchaseService purchaseService;
	
	@Autowired
	RequestUtil requestUtil;
	
	@Autowired
	ObjectMapperUtils objectMapperUtils;

    @Autowired
    private AppUtil appUtil;  

	@Autowired
	ICustomerHistoryService customerHistoryService;

	@Autowired
	com.myplus.business_service.config.TradeSagaProperties tradeSagaProperties;

	@Autowired
	com.myplus.business_service.service.SagaSellService sagaSellService;

	@Autowired
	com.myplus.business_service.service.SagaSaleWriter saleWriter;   // SF-1/SF-2: shared authoritative invoice apply

	@Autowired
	com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

	@Autowired
	com.myplus.commerce.contracts.client.CatalogClient catalogClient;   // M4d: resolve line display fields from catalog

	/** M4d (slice 94): batch-resolve catalog ProductRef by productId for the read screens (name/sku/description),
	 *  replacing the local Item entity load. Best-effort — on a catalog hiccup names fall back to blank, never throws. */
	private java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productRefs(java.util.List<Long> productIds) {
		if (productIds == null || productIds.isEmpty()) return java.util.Collections.emptyMap();
		try {
			return catalogClient.getProducts(productIds).stream()
				.collect(java.util.stream.Collectors.toMap(com.myplus.commerce.contracts.dto.ProductRef::getId, p -> p, (a, b) -> a));
		} catch (Exception e) {
			LOGGER.warn("M4d: catalog getProducts failed for {} id(s); line names may be blank", productIds.size(), e);
			return java.util.Collections.emptyMap();
		}
	}

	@Autowired
	com.myplus.business_service.service.PaymentService paymentService;

	@Autowired
	com.myplus.business_service.service.TaxService taxService;

	@Autowired
	com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;

	@Autowired
	com.myplus.business_service.repository.SaleReturnRepo saleReturnRepo;   // SF-11: return audit / credit-note

	@Autowired
	com.myplus.business_service.service.AuditService auditService;   // #6: append-only audit trail

	@org.springframework.beans.factory.annotation.Autowired
	com.myplus.business_service.service.GlOutboxService glOutboxService;   // #4: durable GL posting via the outbox

	@Autowired
	com.myplus.business_service.service.PeriodLockGuard periodLockGuard;   // period close: reject changes in a locked period

	@Autowired
	com.myplus.business_service.service.StoreCreditService storeCreditService;   // SF-5 Model B: issue credit on returns

	@Autowired
	com.myplus.business_service.service.SaleVoidService saleVoidService;   // O1: the single books-safe reversal

	@Autowired
	com.myplus.common.settings.SettingsService settingsService;   // common-settings: per-org receipt/sale policy toggles

	@Autowired
	com.myplus.business_service.repository.StoreRepository storeRepository;   // B2B-P3g: document letterhead fallback

	@Autowired
	com.myplus.business_service.service.DocumentTemplateService documentTemplateService;   // B2B-P3g: owner layouts

	/**
	 * B2B-P3g — who ISSUED this document, for the top of a printed invoice.
	 *
	 * <p>Owner settings win; the store the invoice was raised at fills the gaps. The ORGANISATION's name is
	 * deliberately not consulted: it lives in auth-service, and putting a cross-service call on the print
	 * path would mean another service being down stops a shop printing a receipt. When neither source has a
	 * name the client falls back to the vertical brand, which is exactly today's behaviour.
	 *
	 * <p>Best-effort throughout: a letterhead is decoration ON a document and must never be the reason one
	 * fails to print — the same rule batch traceability follows below.
	 */
	private com.myplus.business_service.dto.LetterheadDTO letterheadFor(CustomerHistory ch) {
		com.myplus.business_service.dto.LetterheadDTO lh = new com.myplus.business_service.dto.LetterheadDTO();
		try {
			lh.setBusinessName(settingsService.getText("pos.document.businessName"));
			lh.setAddressLine1(settingsService.getText("pos.document.addressLine1"));
			lh.setAddressLine2(settingsService.getText("pos.document.addressLine2"));
			lh.setPhone(settingsService.getText("pos.document.phone"));
			lh.setLogoUrl(settingsService.getText("pos.document.logoUrl"));
			lh.setLicenseNo(settingsService.getText("pos.document.licenseNo"));
			lh.setLicenseExpiry(settingsService.getText("pos.document.licenseExpiry"));

			if (ch.getStoreId() != null) {
				storeRepository.findById(ch.getStoreId())
					// Anti-IDOR: an invoice carries a store id, but we still only read a store of OUR tenant.
					.filter(s -> inMyTenant(s.getOrganizationId(), s.getUserId()))
					.ifPresent(s -> {
						lh.setStoreName(s.getName());
						if (lh.getBusinessName() == null) lh.setBusinessName(s.getName());
						if (lh.getAddressLine1() == null) lh.setAddressLine1(s.getAddress());
						if (lh.getPhone() == null) lh.setPhone(s.getPhone());
					});
			}
		} catch (Exception e) {
			LOGGER.error("Could not resolve the document letterhead for invoice {}", ch.getInvoiceNo(), e);
		}
		return lh;
	}

	ModelMapper modelMapper = new ModelMapper();
	{
		modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
	}

	private static java.math.BigDecimal nzbd(java.math.BigDecimal v) { return v != null ? v : java.math.BigDecimal.ZERO; }
	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	/** True if a row (by its org/user) belongs to the caller's tenant, incl. their pre-migration org-NULL rows. */
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}
	/** P2c anti-IDOR: the list queries filter by store, but these endpoints take an id from the client — so the
	 *  store rule is re-applied per record. Without it an admin at Store B can open/edit a Store-A invoice by id. */
	private boolean myStore(Long rowStore) {
		return requestUtil.canAccessStore(rowStore);
	}

	/** Role-aware visibility (Phase 7a): owner/super AND admin see the WHOLE org's data; a plain user sees
	 *  only their own. Privileges travel in the JWT -> gateway X-User-Privileges -> authorities. Centralised
	 *  in RequestUtil so Customer/Sell/Purchase share one rule. */
	private boolean seesAllOrg() {
		return requestUtil.callerSeesWholeOrg();
	}

	/**
	 * Role×location visible sells. Empty store grants => no store filter (single-store / unassigned /
	 * legacy => current behaviour). Non-empty => constrain to those stores. Role: whole-org viewer sees all
	 * users in scope; a plain user sees only their own.
	 */
	private List<Sell> visibleSells() {
		if (requestUtil.isOwnerSuper())                       // owner: whole org, all stores, always
			return sellService.findScoped(orgId(), userId());
		java.util.Set<Long> stores = requestUtil.accessibleStoreIds();
		if (stores.isEmpty())
			return seesAllOrg() ? sellService.findScoped(orgId(), userId())
			                    : sellService.findOwnScoped(orgId(), userId());
		return seesAllOrg() ? sellService.findScopedByStores(orgId(), stores)
		                    : sellService.findOwnScopedByStores(orgId(), userId(), stores);
	}


// In SellService — map Sell to SellDTO manually
// public SellDTO toDTO(Sell sell) {
//     SellDTO dto = new SellDTO();
//     // dto.setSrp(sell.getSellId());
//     dto.setQuantity(sell.getQuantity());
//     // dto.setSellRate(sell.getSellRate());
//     // dto.setDiscount(sell.getDiscount());
//     dto.setTotalAmount(sell.getTotalAmount());
//     dto.setNetAmount(sell.getNetAmount());
//     dto.setUpdated(""+sell.getDated());

//     // Map stock — only simple fields, no deep nesting
//     if (sell.getStock() != null) {
//         StockDTO stockDTO = new StockDTO();
//         stockDTO.setStockId(sell.getStock().getStockId());
//         stockDTO.setBsellRate(sell.getStock().getBsellRate());
//         stockDTO.setBpurchaseRate(sell.getStock().getBpurchaseRate());
//         dto.setStockDTO(stockDTO);
//     }

//     // Map customerHistory — stop at one level deep
//     if (sell.getCustomerHistory() != null) {
//         CustomerHistoryDTO chDTO = new CustomerHistoryDTO();
//         chDTO.setId(sell.getCustomerHistory().getId());
//         chDTO.setDated(sell.getCustomerHistory().getDated());
//         chDTO.setPaidAmount(sell.getCustomerHistory().getPaidAmount());
//         chDTO.setDueAmount(sell.getCustomerHistory().getDueAmount());
//         chDTO.setDueDate(sell.getCustomerHistory().getDueDate());

//         // Map customer inside history — stop here, don't go back to history
//         if (sell.getCustomerHistory().getCustomer() != null) {
//             CustomerDTO customerDTO = new CustomerDTO();
//             customerDTO.setId(sell.getCustomerHistory().getCustomer().getId());
//             customerDTO.setName(sell.getCustomerHistory().getCustomer().getName());
//             customerDTO.setContact(sell.getCustomerHistory().getCustomer().getContact());
//             customerDTO.setDueAmount(sell.getCustomerHistory().getCustomer().getDueAmount());
// 			customerDTO.setPaidAmount(sell.getCustomerHistory().getCustomer().getPaidAmount());
// 			customerDTO.setDueAmount(sell.getCustomerHistory().getCustomer().getDueAmount());
// 			customerDTO.setDueDate(sell.getCustomerHistory().getCustomer().getDueDate());
//             chDTO.setCustomerDTO(customerDTO);
//         }
//         dto.setCustomerHistory(chDTO);
//     }

//     return dto;
// }	
	@RequestMapping(value = "/getUserSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserSell(@RequestParam(required=false) Integer page,
			@RequestParam(required=false) Integer size, final HttpServletRequest request) {
		try {
			String offset = request.getParameter("q");
			// tenant-scoped, newest-first. slice 24: page&size -> DB page; else legacy "recent N" offset cap.
			// Role×location-aware. The paged org-wide query runs only for a whole-org viewer with NO store
			// constraint; otherwise the store-aware visible list (own for a plain user). Never leaks the org.
			boolean pagedWholeOrg = requestUtil.isOwnerSuper()
					|| (seesAllOrg() && requestUtil.accessibleStoreIds().isEmpty());   // no store constraint
			List<Sell> objs = (page != null && size != null && pagedWholeOrg)
					? sellService.findScoped(orgId(), userId(), org.springframework.data.domain.PageRequest.of(page, size))
					: visibleSells();
			if((page == null || size == null) && !(appUtil.isEmptyOrNull(offset) || offset.equals("-1"))) {
				int limit = Integer.valueOf(offset);
				if(objs.size() > limit) objs = new ArrayList<>(objs.subList(0, limit));
			}

			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
			// M4e.d (slice 106): line display fields from catalog ProductRef by productId (no Item load, no reverse map).
			java.util.List<Long> sagaProductIds = objs.stream()
					.filter(s -> s.getProductId() != null)
					.map(s -> s.getProductId()).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(sagaProductIds);
			List<SellDTO> dtos=new ArrayList<SellDTO>();
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				// SellDTO dto = appUtil.objTodtoConverter(o);
				SellDTO dto = modelMapper.map(o, SellDTO.class);
				if(o.getProductId() != null) {
					// M4d (slice 94): name/sku/description from catalog; itemId from the reverse map (picker).
					com.myplus.commerce.contracts.dto.ProductRef p = productById.get(o.getProductId());
					if(p != null) {
						dto.setItemName(p.getName());
						dto.setItemCode(p.getSku());
						dto.setDescription(p.getDescription());
					}

					if (o.getCustomerHistory() != null) {
						CustomerHistoryDTO customerHistoryDTO = modelMapper.map(o.getCustomerHistory(), CustomerHistoryDTO.class);
						dto.setCustomerHistory(customerHistoryDTO);

						if (o.getCustomerHistory().getCustomer() != null) {
							CustomerDTO customerDTO = modelMapper.map(o.getCustomerHistory().getCustomer(), CustomerDTO.class);
							dto.setCustomer(customerDTO);
						}
					}


					dtos.add(dto);
				}
			});
			return new GenericResponse("SUCCESS",
					messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}

	/**
	 * Load a full sale (invoice) for editing. Given ANY of its line items' sellId, returns the parent
	 * invoice's customer + amounts + ALL its line items so the cart (iDiv) and sell form can be rebuilt.
	 * Tenant-scoped (anti-IDOR): a sellId from another org returns NOT_FOUND without revealing it exists.
	 */
	@RequestMapping(value = "/getSellInvoice", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getSellInvoice(@RequestParam("sellId") Long sellId, final HttpServletRequest request) {
		try {
			Optional<Sell> os = sellService.findById(sellId);
			if (!os.isPresent()) return new GenericResponse("NOT_FOUND", "Sale not found");
			Sell clicked = os.get();
			if (!inMyTenant(clicked.getOrganizationId(), clicked.getUserId()))
				return new GenericResponse("NOT_FOUND", "Sale not found"); // anti-IDOR (cross-org)
			if (!myStore(clicked.getStoreId()))
				return new GenericResponse("NOT_FOUND", "Sale not found"); // anti-IDOR (cross-store)
			// Role-aware: a non-SUPER caller may only open invoices they created.
			if (!seesAllOrg() && clicked.getUserId() != null && !clicked.getUserId().equals(userId()))
				return new GenericResponse("NOT_FOUND", "Sale not found");
			if (clicked.getCustomerHistory() == null || clicked.getCustomerHistory().getCustomer_history_id() == null)
				return new GenericResponse("NOT_FOUND", "This sale has no invoice to edit");

			Long chId = clicked.getCustomerHistory().getCustomer_history_id();
			List<Sell> lines = sellService.findByInvoiceScoped(chId, orgId(), userId());
			if (appUtil.isEmptyOrNull(lines)) return new GenericResponse("NOT_FOUND", "No line items found");

			CustomerHistory ch = clicked.getCustomerHistory();
			CustomerHistoryDTO out = new CustomerHistoryDTO();
			out.setCustomer_history_id(ch.getCustomer_history_id());
			out.setInvoiceNo(ch.getInvoiceNo());
			out.setInvoiceSeq(ch.getInvoiceSeq());
			out.setPaidAmount(ch.getPaidAmount());
			out.setDueAmount(ch.getDueAmount());
			out.setDueDate(ch.getDueDate());
			if (ch.getCustomer() != null) {
				out.setCustomer(modelMapper.map(ch.getCustomer(), CustomerDTO.class));
			}

			// M4e.d (slice 106): line names from catalog ProductRef by productId (no Item load, no reverse map).
			java.util.List<Long> invProductIds = lines.stream()
					.filter(s -> s.getProductId() != null).map(Sell::getProductId).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(invProductIds);

			// B2B-P3b-2 (#4): every batch for every line of this invoice in ONE query, then grouped in memory.
			// A per-line query would put N round trips on a receipt print, which is exactly the pattern the
			// price quote and the tax lookup were both designed to avoid.
			java.util.Map<Long, java.util.List<com.myplus.business_service.dto.SellBatchDTO>> batchesBySell =
					new java.util.HashMap<>();
			try {
				java.util.List<Long> sellIds = lines.stream().map(Sell::getSellId)
						.filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
				if (!sellIds.isEmpty()) {
					for (com.myplus.business_service.entity.SellBatch b : sellBatchRepo.findBySellIds(sellIds)) {
						batchesBySell.computeIfAbsent(b.getSellId(), k -> new java.util.ArrayList<>())
								.add(new com.myplus.business_service.dto.SellBatchDTO(
										b.getBatchNo(), b.getExpiryDate(), b.getQuantity()));
					}
				}
			} catch (Exception e) {
				// Traceability is decoration ON a receipt, never a reason to fail printing one.
				LOGGER.warn("Could not load batch traceability for invoice {}", ch.getInvoiceNo(), e);
			}
			out.setBalanceAfter(ch.getBalanceAfter());

			List<SellDTO> sales = new java.util.ArrayList<>();
			for (Sell s : lines) {
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				SellDTO sd = modelMapper.map(s, SellDTO.class);
				com.myplus.commerce.contracts.dto.ProductRef p = productById.get(s.getProductId());
				if (p != null) { sd.setItemName(p.getName()); sd.setItemCode(p.getSku()); }
				sd.setBatches(batchesBySell.getOrDefault(s.getSellId(), java.util.List.of()));
				sales.add(sd);
			}
			out.setSales(sales);
			return new GenericResponse("SUCCESS", "Invoice loaded", out);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getSellInvoice " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the sale. Please try again.");
		}
	}

	/**
	 * G6 receipts (slice 38): the printable receipt for an invoice, by its per-org invoice number. Carries the
	 * lines (with per-line tax + item name, saga or legacy), the G3 tax totals, the G5 payment summary and the
	 * tax label/reg-no — everything the client renders into a thermal/A4 receipt. Tenant-scoped + role-aware.
	 */
	@RequestMapping(value = "/getReceipt", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getReceipt(@RequestParam("invoiceNo") String invoiceNo, final HttpServletRequest request) {
		try {
			if (appUtil.isEmptyOrNull(invoiceNo)) return new GenericResponse("NOT_FOUND", "Invoice not found");
			CustomerHistory ch = customerHistoryRepo.findByOrganizationIdAndInvoiceNo(orgId(), invoiceNo).orElse(null);
			if (ch == null || !inMyTenant(ch.getOrganizationId(), ch.getUserId()) || !myStore(ch.getStoreId()))
				return new GenericResponse("NOT_FOUND", "Invoice not found");           // anti-IDOR (org + store)
			if (!seesAllOrg() && ch.getUserId() != null && !ch.getUserId().equals(userId()))
				return new GenericResponse("NOT_FOUND", "Invoice not found");           // role-aware

			List<Sell> lines = sellService.findByInvoiceScoped(ch.getCustomer_history_id(), orgId(), userId());
			if (appUtil.isEmptyOrNull(lines)) return new GenericResponse("NOT_FOUND", "No line items found");

			CustomerHistoryDTO out = new CustomerHistoryDTO();
			out.setCustomer_history_id(ch.getCustomer_history_id());
			out.setInvoiceNo(ch.getInvoiceNo());
			out.setInvoiceSeq(ch.getInvoiceSeq());
			out.setDated(ch.getDated());
			out.setPaidAmount(ch.getPaidAmount());
			out.setDueAmount(ch.getDueAmount());
			out.setDueDate(ch.getDueDate());
			out.setSubTotal(ch.getSubTotal());
			out.setTaxTotal(ch.getTaxTotal());
			out.setGrandTotal(ch.getGrandTotal());
			out.setPaymentMode(ch.getPaymentMode());
			out.setTenderedAmount(ch.getTenderedAmount());
			out.setChangeAmount(ch.getChangeAmount());
			if (ch.getCustomer() != null) out.setCustomer(modelMapper.map(ch.getCustomer(), CustomerDTO.class));

			// SF-5 Model B: store credit applied on this sale (Σ STORE_CREDIT tenders) — printed on the receipt.
			out.setStoreCreditApplied(paymentService.forInvoice(ch.getCustomer_history_id()).stream()
					.filter(p -> p.getMethod() == com.myplus.business_service.entity.PaymentMethod.STORE_CREDIT)
					.map(p -> nzbd(p.getAmount())).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));

			var ts = taxService.settingsFor(orgId());                                  // tax label/reg-no for the header
			out.setTaxLabel(ts.getTaxLabel());
			out.setTaxRegNo(ts.getTaxRegNo());
			// common-settings: owner's per-rate tax-breakdown preference (default true) rides on the receipt.
			out.setShowTaxBreakdown(settingsService.getBool("pos.receipt.showTaxBreakdown"));
			// #13: the promo footer. getBool returns false for an unset key, which is the intended default —
			// a paying customer must never find it on their invoices without having chosen it.
			out.setShowPromo(settingsService.getBool("pos.receipt.showPromo"));

			// B2B-P3g: the document's identity and format. The layout itself is chosen client-side from the
			// buyer's channel (a trade account books an invoice, a walk-in gets a till slip); this only
			// carries the org's OVERRIDE of that rule, plus the letterhead and the printed wording.
			out.setTradeDiscount(ch.getTradeDiscount());     // B2B-P3g (V35): invoice-level concession
			out.setBookedByName(ch.getBookedByName());       // stamped at write, never resolved at print
			// B2B-P4b: the buyer's PO, carried from the quote. It must reach the PRINTED invoice — that is the
			// number their accounts-payable clerk matches against their own purchase order.
			out.setCustomerPoNumber(ch.getCustomerPoNumber());
			out.setLetterhead(letterheadFor(ch));
			out.setLayoutMode(settingsService.getChoice("pos.document.layoutMode",
					java.util.Set.of("auto", "thermal", "a4"), "auto"));
			out.setCurrencySymbol(settingsService.getText("pos.document.currencySymbol"));
			out.setCurrencyWord(settingsService.getText("pos.document.currencyWord"));
			out.setCurrencyFraction(settingsService.getText("pos.document.currencyFraction"));
			out.setFooterText(settingsService.getText("pos.document.footerText"));
			out.setShowAmountInWords(settingsService.getBool("pos.document.amountInWords"));

			// 3g-3: the org's own layout for this buyer's channel, if they have designed one. Null means the
			// browser uses a built-in preset — which is what every tenant gets until they open the designer,
			// and is byte-for-byte today's receipt. Sent as the parsed profile rather than a JSON string so
			// the renderer consumes one shape whether the layout came from here or from a preset.
			try {
				String channel = (ch.getCustomer() != null && ch.getCustomer().getCustomerType() != null
						&& ch.getCustomer().getCustomerType().isB2B()) ? "B2B" : "B2C";
				String profileJson = documentTemplateService.resolveProfileJson(orgId(), userId(), channel);
				if (profileJson != null) {
					out.setDocumentProfile(new com.fasterxml.jackson.databind.ObjectMapper().readTree(profileJson));
				}
			} catch (Exception e) {
				// A layout is a preference, never a reason a shop cannot print. Same rule as the letterhead.
				LOGGER.error("Could not attach a document layout to invoice {}", ch.getInvoiceNo(), e);
			}

			// M4d (slice 94): line names from catalog ProductRef (a printed receipt needs no itemId) — no Item load.
			java.util.List<Long> sagaProductIds = lines.stream()
					.filter(s -> s.getProductId() != null)
					.map(Sell::getProductId).distinct().collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(sagaProductIds);
			// B2B-P3b-2 (#4): every batch for every line of this invoice in ONE query, then grouped in
			// memory. A per-line query would put N round trips on a receipt print.
			java.util.Map<Long, java.util.List<com.myplus.business_service.dto.SellBatchDTO>> batchesBySell =
					new java.util.HashMap<>();
			try {
				java.util.List<Long> sellIds = lines.stream().map(Sell::getSellId)
						.filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toList());
				if (!sellIds.isEmpty()) {
					for (com.myplus.business_service.entity.SellBatch b : sellBatchRepo.findBySellIds(sellIds)) {
						batchesBySell.computeIfAbsent(b.getSellId(), k -> new java.util.ArrayList<>())
								.add(new com.myplus.business_service.dto.SellBatchDTO(
										b.getBatchNo(), b.getExpiryDate(), b.getQuantity()));
					}
				}
			} catch (Exception e) {
				// Traceability is decoration ON a receipt, never a reason to fail printing one.
				LOGGER.error("Could not load batch traceability for invoice {}", ch.getInvoiceNo(), e);
			}
			out.setBalanceAfter(ch.getBalanceAfter());

			List<SellDTO> sales = new java.util.ArrayList<>();
			for (Sell s : lines) {
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				SellDTO sd = modelMapper.map(s, SellDTO.class);
				com.myplus.commerce.contracts.dto.ProductRef p = productById.get(s.getProductId());
				// B2B-P3g: `packing` is the catalog product's existing unit — the ProductRef is already loaded
				// here for the line name, so the trade invoice's Packing column costs no extra query.
				if (p != null) { sd.setItemName(p.getName()); sd.setItemCode(p.getSku()); sd.setPacking(p.getUnit()); }
				sd.setBatches(batchesBySell.getOrDefault(s.getSellId(), java.util.List.of()));
				sales.add(sd);
			}
			out.setSales(sales);
			return new GenericResponse("SUCCESS", "Receipt loaded", out);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getReceipt " + e.getMessage(), e);
			return new GenericResponse("ERROR", "Could not load the receipt.");
		}
	}

	@RequestMapping(value = "/loadSR", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse loadSR(final SellDTO dto, final HttpServletRequest request) {
		int CURRENT_MONTH = 0;
		try {
			AuthenticatedUser user = requestUtil.getCurrentUser();
	        List<Sell> objs=null;
	        if(dto.getRp() == CURRENT_MONTH) {
	        	objs = sellService.findSellByDates(appUtil.firstDateTimeOfMonth(),appUtil.lastDateTimeOfMonth(), user.getOrganizationId(), user.getUserId());
	        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByDates(appUtil.getDateTime(dto.getSd()), appUtil.getDateTime(dto.getEd()), user.getOrganizationId(), user.getUserId());
	        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByStartDate(appUtil.getDateTime(dto.getSd()), user.getOrganizationId(), user.getUserId());
	        }else if(appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByEndDate(appUtil.getDateTime(dto.getEd()), user.getOrganizationId(), user.getUserId());
//	        }else {
//	        	//current month
//	        	
//				objs = sellService.findAll(example);
	        }
	        
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			// M4e.d (slice 106): line names from catalog ProductRef by productId (no Item load, no reverse map).
			// itemStock (live on-hand) dropped — this is a sales report, not a stock report.
			java.util.List<Long> rpProductIds = objs.stream().filter(s -> s.getProductId() != null)
					.map(Sell::getProductId).distinct().collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> rpProductById = productRefs(rpProductIds);
			List<SellDTO> dtos=new ArrayList<SellDTO>();
			objs.forEach(obj ->{
				SellDTO dtotemp = modelMapper.map(obj, SellDTO.class);
				com.myplus.commerce.contracts.dto.ProductRef p = rpProductById.get(obj.getProductId());
				if(p != null) {
					dtotemp.setItemName(p.getName());
					dtotemp.setItemCode(p.getSku());
					dtotemp.setDescription(p.getDescription());
					dtotemp.setCategory(p.getCategory());   // B2B-P3e-1 (#6): report dimension
				}
				// Sale report: flatten the invoice (CustomerHistory) + Customer onto the line so the UI can show
				// invoice #, who bought, how they paid and what's still owed without a second round-trip.
				CustomerHistory ch = obj.getCustomerHistory();
				if (ch != null) {
					dtotemp.setInvoiceNo(ch.getInvoiceNo());
					dtotemp.setPaymentMode(ch.getPaymentMode());
					dtotemp.setDueAmount(ch.getDueAmount());
					dtotemp.setGrandTotal(ch.getGrandTotal());
					dtotemp.setDueDate(ch.getDueDate() != null ? ch.getDueDate().toString() : "");
					if (ch.getCustomer() != null) {
						dtotemp.setCn(ch.getCustomer().getName());
						dtotemp.setCc(ch.getCustomer().getContact());
						// B2B-P3e-1 (#6): report dimensions — who bought, and on which channel.
						dtotemp.setCustomerId(ch.getCustomer().getCustomerId());
						dtotemp.setCustomerType(ch.getCustomer().getCustomerType() != null
								? ch.getCustomer().getCustomerType().name() : null);
					}
				}
				dtotemp.setDated(appUtil.getDateStr(obj.getDated()));
				dtotemp.setUpdated(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dtotemp);
			});

			// B2B-P3e-1 (#6): narrow by customer / product / category / channel. The filter binds from the
			// SAME posted form (its fields live on SellDTO), and every field is optional — an empty filter
			// returns exactly what this report returned before, which is what makes it safe for live
			// tenants. It NARROWS an already org-scoped result and can never widen it.
			com.myplus.business_service.dto.SaleReportFilter filter =
					com.myplus.business_service.dto.SaleReportFilter.builder()
							.customerId(dto.getCustomerId()).productId(dto.getProductId())
							.category(dto.getCategory()).customerType(dto.getCustomerType())
							.build();
			// Filtered into its own variable: `dtos` is captured by the mapping lambda above, so reassigning
			// it here would make it not effectively final and fail to compile.
			List<SellDTO> reportRows = filter.isEmpty() ? dtos
					: dtos.stream().filter(filter.asPredicate())
							.collect(java.util.stream.Collectors.toList());

			// B2B-P3e-2 (#6): when a grouping is asked for, return the subtotals ALONGSIDE the detail from this
			// same query — one round trip, and the two views cannot disagree because they are the same rows.
			// Aggregates the FILTERED rows, so narrowing the report narrows its subtotals too.
			// An unrecognised groupBy simply means ungrouped; a stale bookmark must not fail a report.
			com.myplus.business_service.dto.SaleReportGrouping grouping =
					com.myplus.business_service.dto.SaleReportGrouping.from(dto.getGroupBy());
			GenericResponse ok = new GenericResponse("SUCCESS",
					messages.getMessage("message.userNotFound", null, request.getLocale()), reportRows);
			if (grouping != null) ok.setObject(grouping.aggregate(reportRows));
			return ok;
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	/**
	 * B2B-P3e-1 (#6): the sale report as a downloadable CSV.
	 *
	 * <p>It calls {@link #loadSR} itself rather than repeating the query — so the file cannot disagree with
	 * the screen, and every filter the user set applies to the export by construction. This is the same
	 * guarantee 3d established for statements, and the reason filters are server-side at all: a client-side
	 * filter would produce a file that quietly ignored them.
	 *
	 * <p>An empty result yields a header-only file rather than an error: "no sales matched" is a valid
	 * answer to a report, not a failure.
	 */
	@RequestMapping(value = "/saleReport.csv", method = {RequestMethod.GET, RequestMethod.POST},
			produces = "text/csv; charset=UTF-8")
	@ResponseBody
	public org.springframework.http.ResponseEntity<String> saleReportCsv(final SellDTO dto,
			final HttpServletRequest request) {
		try {
			GenericResponse resp = loadSR(dto, request);

			// B2B-P3e-2 (#6): if the screen is grouped, the file is grouped. A grouped screen with a
			// detail-level export would hand the customer a different document from the one they can see.
			com.myplus.business_service.dto.SaleReportGrouping grouping =
					com.myplus.business_service.dto.SaleReportGrouping.from(dto.getGroupBy());
			if (grouping != null) {
				java.util.List<java.util.List<?>> grouped = new java.util.ArrayList<>();
				Object obj = resp != null ? resp.getObject() : null;
				if (obj instanceof java.util.List) {
					for (Object o : (java.util.List<?>) obj) {
						if (!(o instanceof com.myplus.business_service.dto.SaleReportGroup)) continue;
						com.myplus.business_service.dto.SaleReportGroup g =
								(com.myplus.business_service.dto.SaleReportGroup) o;
						grouped.add(java.util.Arrays.asList(g.getLabel(), g.getInvoices(), g.getQuantity(),
								g.getTotal(), g.getTax(), g.getGross()));
					}
				}
				String groupedCsv = com.myplus.business_service.util.CsvWriter.write(
						java.util.Arrays.asList(grouping.name(), "Invoices", "Qty", "Total", "Tax", "Gross"),
						grouped);
				return org.springframework.http.ResponseEntity.ok()
						.header("Content-Disposition", "attachment; filename=\"sale-report.csv\"")
						.header("Content-Type", "text/csv; charset=UTF-8")
						.body(groupedCsv);
			}

			java.util.List<java.util.List<?>> rows = new java.util.ArrayList<>();
			Object coll = resp != null ? resp.getCollection() : null;
			if (coll instanceof java.util.List) {
				for (Object o : (java.util.List<?>) coll) {
					if (!(o instanceof SellDTO)) continue;
					SellDTO r = (SellDTO) o;
					rows.add(java.util.Arrays.asList(
							r.getDated(), r.getInvoiceNo(), r.getCn(), r.getCustomerType(),
							r.getItemCode(), r.getItemName(), r.getCategory(),
							r.getQuantity(), r.getSellRate(), r.getTotalAmount(), r.getTaxAmount()));
				}
			}
			String csv = com.myplus.business_service.util.CsvWriter.write(
					java.util.Arrays.asList("Date", "Invoice", "Customer", "Channel", "SKU", "Item",
							"Category", "Qty", "Rate", "Total", "Tax"),
					rows);
			return org.springframework.http.ResponseEntity.ok()
					.header("Content-Disposition", "attachment; filename=\"sale-report.csv\"")
					.header("Content-Type", "text/csv; charset=UTF-8")
					.body(csv);
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return org.springframework.http.ResponseEntity.status(400).body("Could not build the report.");
		}
	}

	@RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSell(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now org + role + location scoped (see visibleSells()).
			List<Sell> objs = visibleSells();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<SellDTO> dtos=new ArrayList<SellDTO>();
			objs.forEach(obj ->{
				SellDTO dto = modelMapper.map(obj, SellDTO.class);
				dto.setDated(appUtil.getDateStr(obj.getDated()));
				dto.setUpdated(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addSell", method = RequestMethod.POST)
	@ResponseBody
	@Transactional
	public GenericResponse addSell(@RequestBody final CustomerHistoryDTO dto, final HttpServletRequest request) {
		try {
			if (dto == null || appUtil.isEmptyOrNull(dto.getSales()))
				return new GenericResponse("ERROR", "No sales data provided");

			// M3c.4d (slice 86): the inventory reservation saga (catalog price + FEFO reserve/confirm) is the ONLY
			// sell-write path — the legacy branch that decremented local Stock has been retired (Stock is being
			// dropped). SagaSellService persists customer + invoice header + lines and manages its own committed
			// transactions (REQUIRES_NEW), so it is safe to call inside this @Transactional.
			String invoiceNo = sagaSellService.addSell(dto);
			String msg = invoiceNo != null
					? "Sale recorded successfully. Invoice " + invoiceNo
					: "Sale recorded successfully.";
			// #3: the sale went through, but anything the margin policy flagged must reach the cashier —
			// a warning that only reaches the log is a warning nobody acts on.
			if (dto.getWarnings() != null && !dto.getWarnings().isEmpty()) {
				msg = msg + "  " + String.join("  ", dto.getWarnings());
			}
			return new GenericResponse("SUCCESS", msg, invoiceNo);

		} catch (com.myplus.business_service.service.PeriodClosedException pce) {
			// Period close: the books are locked through the sale's date — surface the reason (nothing written yet).
			LOGGER.warn("addSell rejected (period closed): {}", pce.getMessage());
			return new GenericResponse("FAILED", pce.getMessage());
		} catch (com.myplus.business_service.service.InsufficientStockException stock) {
			// Business rejection (FEFO reserve refused) — nothing was written yet, so surface the reason to the
			// cashier verbatim instead of the generic "unexpected error". No rollback drama, just a clean ERROR.
			LOGGER.warn("addSell rejected (insufficient stock): {}", stock.getMessage());
			return new GenericResponse("ERROR", stock.getMessage());
		} catch (com.myplus.business_service.service.CreditConfirmationRequiredException confirm) {
			// B2B-P1 (#9): over the credit limit under policy=warn, not yet acknowledged. NOTHING was written
			// and NO stock was reserved. A distinct CONFIRM status, not ERROR — nothing failed; the operator
			// simply has not answered yet. The client asks with the shared dialog and re-submits with
			// creditAcknowledged=true (same idempotencyKey, which is safe because nothing was recorded).
			LOGGER.info("addSell awaiting credit-limit confirmation: {}", confirm.getMessage());
			return new GenericResponse("CONFIRM", confirm.getMessage());
		} catch (com.myplus.common.web.exception.ValidationException clinical) {
			// B1: a clinical/business rule refused the sale (e.g. prescription-only medicine with no prescription).
			// Nothing was written — surface the reason verbatim, exactly like the stock rejection above, or the
			// generic handler below would bury it under "An unexpected error occurred".
			LOGGER.warn("addSell rejected (clinical rule): {}", clinical.getMessage());
			return new GenericResponse("ERROR", clinical.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addSell "+e.getCause(), e);
			// Propagate past the @Transactional boundary so customer + history + sell roll back
			// together (all-or-nothing); handleUncaught() rebuilds the ERROR envelope.
			throw new RuntimeException("An unexpected error occurred. Please contact support.", e);
		}
	}

	/**
	 * Rebuilds the GenericResponse("ERROR", …) envelope for an exception that propagated out of a
	 * @Transactional endpoint (addSell). By the time this runs the transaction has rolled back, so the
	 * multi-write (customer + customer-history + sell/stock) is all-or-nothing.
	 */
	// A @PreAuthorize denial throws AccessDeniedException. This controller's broad Exception handler above would
	// otherwise swallow it into a 200 "ERROR" envelope — the op is still blocked, but the status would mislead.
	// A more-specific handler wins, so access denials return a clean 403 like every other controller.
	@ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
	public org.springframework.http.ResponseEntity<GenericResponse> handleAccessDenied(
			org.springframework.security.access.AccessDeniedException e) {
		return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
				.body(new GenericResponse("FORBIDDEN", "Access denied"));
	}

	@ExceptionHandler(Exception.class)
	public GenericResponse handleUncaught(Exception e) {
		return new GenericResponse("ERROR", e.getMessage());
	}
		
	
	/**
	 * Update an existing sale (invoice) in place — the "edit" counterpart of addSell. Keeps the SAME
	 * invoice number; adjusts stock by the NET per-item delta (old sold qty given back − new sold qty);
	 * recomputes the customer due. All-or-nothing (@Transactional). The frontend routes here (instead of
	 * addSell) when it carries a customer_history_id (an edit in progress).
	 */
	/** M4e.d (slice 106): a sell line's catalog productId — productId-native (the Item/ItemCatalogMap bridge is gone). */
	private Long productIdOfLine(SellDTO s) {
		return (s.getProductId() != null && s.getProductId() > 0) ? s.getProductId() : null;
	}

	@RequestMapping(value = "/updateSell", method = RequestMethod.POST)
	@ResponseBody
	@Transactional
	public GenericResponse updateSell(@RequestBody final CustomerHistoryDTO dto, final HttpServletRequest request) {
		try {
			if (dto == null || dto.getCustomer_history_id() == null)
				return new GenericResponse("ERROR", "No invoice id provided for update");
			if (appUtil.isEmptyOrNull(dto.getSales()))
				return new GenericResponse("ERROR", "No sales data provided");

			AuthenticatedUser user = requestUtil.getCurrentUser();
			Long chId = dto.getCustomer_history_id();

			// Anti-IDOR: the invoice must belong to this tenant.
			Optional<CustomerHistory> chOpt = customerHistoryService.findById(chId);
			if (!chOpt.isPresent())
				return new GenericResponse("NOT_FOUND", "Invoice not found");
			CustomerHistory ch = chOpt.get();
			if (!inMyTenant(ch.getOrganizationId(), ch.getUserId()) || !myStore(ch.getStoreId()))
				return new GenericResponse("NOT_FOUND", "Invoice not found");   // anti-IDOR (org + store)
			if ("VOID".equals(ch.getStatus()))   // Audit #3: a voided invoice is read-only
				return new GenericResponse("FAILED", "This invoice is voided and cannot be edited.");
			// SF-5 Model B: a sale settled with store credit can't be edited in place (the shared apply path would
			// re-settle the tender without reconciling the credit ledger). Void it and re-enter instead.
			if (paymentService.forInvoice(chId).stream()
					.anyMatch(p -> p.getMethod() == com.myplus.business_service.entity.PaymentMethod.STORE_CREDIT))
				return new GenericResponse("FAILED", "This sale was paid with store credit — void it and re-enter to change it.");
			// Period close: an edit rewrites the ORIGINAL invoice in place, so it must fall in an open period.
			periodLockGuard.assertOpen(ch.getDated() != null ? ch.getDated().toLocalDate() : java.time.LocalDate.now());

			// 1) Net stock change per stock_id = (old sold qty given back) − (new sold qty taken).
			List<Sell> oldLines = sellService.findByInvoiceScoped(chId, orgId(), userId());
			// M3c.3b (slice 80): net per-PRODUCT delta = old sold qty − new sold qty. Adjust INVENTORY (not local
			// Stock): a positive delta returns the excess (importStock); a negative delta takes more via one
			// reservation+confirm (reject the edit if out of stock). Edits become inventory-correct and Stock-free.
			java.util.Map<Long, Float> delta = new java.util.HashMap<>();
			for (Sell o : oldLines) {
				Long pid = o.getProductId();   // M3c.4a: productId-only (backfill complete)
				if (pid != null && o.getQuantity() != null) delta.merge(pid, o.getQuantity(), Float::sum);
			}
			for (SellDTO s : dto.getSales()) {
				Long pid = productIdOfLine(s);
				if (pid != null && s.getQuantity() != null) delta.merge(pid, -s.getQuantity(), Float::sum);
			}
			java.util.List<com.myplus.commerce.contracts.dto.StockReservationLine> takeLines = new java.util.ArrayList<>();
			java.util.List<com.myplus.commerce.contracts.dto.StockImportLine> returnLines = new java.util.ArrayList<>();
			for (java.util.Map.Entry<Long, Float> e : delta.entrySet()) {
				float d = e.getValue();
				if (d < 0f) takeLines.add(new com.myplus.commerce.contracts.dto.StockReservationLine(e.getKey(), java.math.BigDecimal.valueOf(-d)));
				else if (d > 0f) returnLines.add(com.myplus.commerce.contracts.dto.StockImportLine.builder().productId(e.getKey()).quantity(d).build());
			}
			if (!takeLines.isEmpty()) {
				com.myplus.commerce.contracts.dto.StockReservationResponse resp = inventoryClient.reserve(
						new com.myplus.commerce.contracts.dto.StockReservationRequest(java.util.UUID.randomUUID().toString(), takeLines));
				if (resp == null || resp.getStatus() != com.myplus.commerce.contracts.dto.ReservationStatus.RESERVED)
					return new GenericResponse("ERROR", "Not enough stock to apply this change.");
				inventoryClient.confirm(resp.getReservationId());
			}
			if (!returnLines.isEmpty()) inventoryClient.importStock(returnLines);

			// 3) Update customer + invoice header IN PLACE — KEEP invoiceSeq/invoiceNo (no new number).
			//    (The old line items are deleted + rewritten authoritatively inside applyInvoice below.)
			Customer customerObj = customerService.saveUpdateCustomer(dto);
			customerService.save(customerObj);
			ch.setCustomer(customerObj);
			ch.setUserId(user.getUserId());
			ch.setOrganizationId(user.getOrganizationId());
			ch.setUpdated(java.time.LocalDateTime.now());
			if (dto.getDueDate() != null) ch.setDueDate(dto.getDueDate());

			// 4) SF-1/SF-2: recompute totals + tax + discount + catalog snapshot and REPLACE the Sell lines through
			//    the SAME authoritative path addSell uses (buildLines → applyInvoice). Settlement keeps the invoice's
			//    prior payment + adds any new tender and derives due = paid − grandTotal (client-sent due not trusted).
			//    Replaces the old hand-rolled block that never recomputed totals and dropped discount/catalogPrice/tax.
			// GL edit adjustment: capture the OLD invoice + COGS BEFORE it's recomputed.
			java.math.BigDecimal oldGrand = nzbd(ch.getGrandTotal()), oldSub = nzbd(ch.getSubTotal()),
					oldTax = nzbd(ch.getTaxTotal()), oldPaid = nzbd(ch.getPaidAmount());
			java.math.BigDecimal oldCost = java.math.BigDecimal.ZERO;
			for (Sell o : oldLines)
				oldCost = oldCost.add(nzbd(o.getCostPrice()).multiply(java.math.BigDecimal.valueOf(o.getQuantity() != null ? o.getQuantity() : 0f)));

			java.util.List<com.myplus.business_service.service.SagaLine> lines =
					sagaSellService.buildLines(dto, new java.util.HashMap<>());

			// B2B-P1 (#9): credit-limit guard on the EDIT path too — an edit can raise what a customer owes
			// just as a new sale can. The invoice's CURRENT unpaid amount is passed so it is not counted
			// twice: the customer's dueAmount already includes it, so without this, merely REDUCING an
			// over-limit invoice would look like a fresh breach and demand confirmation to fix a mistake.
			// CustomerHistory.dueAmount is (paid − grandTotal), negative while owing → negate to get owed.
			java.math.BigDecimal editingDue = nzbd(ch.getDueAmount()).negate();
			if (editingDue.signum() < 0) editingDue = java.math.BigDecimal.ZERO;
			sagaSellService.assertCreditPolicy(dto, lines, editingDue);

			saleWriter.applyInvoice(ch, lines, dto, user, true);

			// GL: reverse the old posting + repost the new (net = the edit's delta) so the books never drift on an
			// edit. The unchanged paid portion cancels between the two. Best-effort — never fail the edit.
			try {
				java.math.BigDecimal newCost = java.math.BigDecimal.ZERO;
				for (com.myplus.business_service.service.SagaLine l : lines)
					if (l.costPrice() != null) newCost = newCost.add(l.costPrice().multiply(java.math.BigDecimal.valueOf(l.quantity())));
				String mode = ch.getPaymentMode();
				if (oldGrand.signum() > 0)
					glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
							.eventType("SALE_RETURN").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
							.grandTotal(oldGrand).subTotal(oldSub).taxTotal(oldTax).cost(oldCost).paidAmount(oldPaid).method(mode).build());
				glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
						.eventType("SALE").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
						.grandTotal(nzbd(ch.getGrandTotal())).subTotal(nzbd(ch.getSubTotal())).taxTotal(nzbd(ch.getTaxTotal()))
						.cost(newCost).paidAmount(nzbd(ch.getPaidAmount())).method(mode).build());
			} catch (Exception glEx) {
				LOGGER.warn(this.getClass().getName() + " > updateSell GL adjustment enqueue failed (edit applied)", glEx);
			}

			auditService.record("SALE_EDIT", "INVOICE", ch.getInvoiceNo(), nzbd(ch.getGrandTotal()), null);   // #6
			return new GenericResponse("SUCCESS", "Sale updated. Invoice " + ch.getInvoiceNo(), ch.getInvoiceNo());
		} catch (com.myplus.business_service.service.PeriodClosedException pce) {
			// Period close: nothing was written before the guard — surface the reason without the generic rollback message.
			LOGGER.warn("updateSell rejected (period closed): {}", pce.getMessage());
			return new GenericResponse("FAILED", pce.getMessage());
		} catch (com.myplus.business_service.service.CreditConfirmationRequiredException confirm) {
			// B2B-P1 (#9): over the credit limit under policy=warn, not yet acknowledged. NOTHING was written
			// and NO stock was reserved. A distinct CONFIRM status, not ERROR — nothing failed; the operator
			// simply has not answered yet. The client asks with the shared dialog and re-submits with
			// creditAcknowledged=true (same idempotencyKey, which is safe because nothing was recorded).
			LOGGER.info("addSell awaiting credit-limit confirmation: {}", confirm.getMessage());
			return new GenericResponse("CONFIRM", confirm.getMessage());
		} catch (com.myplus.common.web.exception.ValidationException clinical) {
			// B1: an edit that introduces a prescription-only line is refused on the same rule as a new sale —
			// updateSell shares buildLines, so the guard applies here too and its reason must survive.
			LOGGER.warn("updateSell rejected (clinical rule): {}", clinical.getMessage());
			return new GenericResponse("ERROR", clinical.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > updateSell " + e.getMessage(), e);
			// Propagate past @Transactional so the whole edit rolls back (all-or-nothing).
			throw new RuntimeException("Could not update the sale. Please try again.", e);
		}
	}

	@PostMapping(value = "/addSelling")
	@ResponseBody
	public GenericResponse addSelling(@RequestBody final List<SellDTO> dtos, final HttpServletRequest request) {
		// M3c.4a (slice 83): legacy local-Stock bulk endpoint, RETIRED. Sells go through addSell (saga, productId,
		// inventory-authoritative). Kept as a stub so the route returns a clear message instead of writing local Stock.
		return new GenericResponse("ERROR", "addSelling is no longer supported; use addSell.");
	}

	@PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
	@RequestMapping(value = "/revertSell", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse reverSell(@Validated final SellDTO dto, final HttpServletRequest request) {
		// M3c.4a (slice 83): legacy local-Stock revert, RETIRED (its UI button was already commented out). Returns/
		// reverts go through saleReturn (saga inverse / inventory restock). Stub so the route can't write local Stock.
		return new GenericResponse("ERROR", "revertSell is no longer supported; use the Sale Return action.");
	}

	/**
	 * Audit #3: hard-delete RETIRED — a raw row delete bypassed inventory restore, AR/AP recompute, GL reversal and
	 * the audit trail, silently drifting the books. Cancellations now go through {@code /voidSell}, which reverses
	 * everything and keeps the record. Stub kept so the route can't 404; it performs no deletion.
	 */
	@PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
	@RequestMapping(value = "/deleteSell", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteSell( HttpServletRequest req, HttpServletResponse resp ){
		LOGGER.warn("deleteSell is retired; use /voidSell (books-safe void). No rows deleted.");
		return false;
	}

	@Transactional
	@PostMapping(value = "/saleReturn")
	@ResponseBody
	public GenericResponse saleReturn(final SellDTO dto, final HttpServletRequest request) {
//	public GenericResponse saleReturn(@RequestParam final Long saleId,@RequestParam final Long stockId,@RequestParam final Float qty) {
		try {
			// sellId identifies the line to return. sellSId (local Stock id) is only needed to restock a legacy
			// local-Stock sell — saga sells have no local Stock (they reverse through inventory below), so an empty
			// sellSId is valid for them and must NOT be rejected here, else the default (saga) sell can't be returned.
			if(appUtil.isEmptyOrNull(dto.getSellId()))
				return new GenericResponse("NOT_FOUND");

			// anti-IDOR: only let the caller return a sale that belongs to their tenant
			Sell existingSell = sellService.findById(dto.getSellId()).orElse(null);
			if(existingSell == null || !inMyTenant(existingSell.getOrganizationId(), existingSell.getUserId())
					|| !myStore(existingSell.getStoreId()))   // a return can only be taken at the store that sold it
				return new GenericResponse("NOT_FOUND");

			// G2 (slice 34) input validation: return qty must be > 0 and not exceed what was sold on this line —
			// otherwise the fallback restock would inflate StockLevel beyond what left. (Bean-Validation standard, slice 26.)
			float soldQty = existingSell.getQuantity() != null ? existingSell.getQuantity() : 0f;
			float retQty = dto.getQuantity() != null ? dto.getQuantity() : 0f;
			if(retQty <= 0f)
				return new GenericResponse("FAILED", "Return quantity must be greater than 0.");
			if(retQty > soldQty)
				return new GenericResponse("FAILED", "Cannot return more than the sold quantity (" + soldQty + ").");

			// Period close: a return posts a new credit dated today, so the CURRENT period must be open.
			periodLockGuard.assertOpen(java.time.LocalDate.now());

			// G2 (slice 34): a saga sell decremented inventory-service (StockEntry/StockLevel), not local Stock.
			// Route its return back through inventory (inverse saga) so on-hand is restored, not just local Stock.
			CustomerHistory ch = existingSell.getCustomerHistory();
			if (ch != null && "VOID".equals(ch.getStatus()))   // Audit #3: no returns against a voided invoice
				return new GenericResponse("FAILED", "This invoice is voided.");
			String reservationId = ch != null ? ch.getReservationId() : null;
			boolean sagaSell = existingSell.getProductId() != null && reservationId != null;

			if(sagaSell) {
				// P11 (slice 55): pharmacy returns quarantine (do not restock) — flag travels from the return UI.
				boolean quarantine = "true".equalsIgnoreCase(request.getParameter("quarantine"));
				com.myplus.commerce.contracts.dto.StockReturnRequest returnReq =
						new com.myplus.commerce.contracts.dto.StockReturnRequest(
								java.util.List.of(new com.myplus.commerce.contracts.dto.StockReturnLine(
										existingSell.getProductId(), dto.getQuantity())));
				returnReq.setQuarantine(quarantine);
				inventoryClient.returnStock(reservationId, returnReq);
			} else if (existingSell.getProductId() != null) {
				// M3c.3 (slice 79): a non-saga sell has a productId (backfill complete) but no reservation — restock
				// INVENTORY by product (a fresh entry) so inventory stays authoritative; no local Stock write.
				inventoryClient.importStock(java.util.List.of(
						com.myplus.commerce.contracts.dto.StockImportLine.builder()
								.productId(existingSell.getProductId()).quantity(dto.getQuantity()).build()));
			}

			// SF-5: the money owed back to the customer is settled in the header re-settle below, from the actual
			// OVERPAYMENT the return creates (paidAmount − new grandTotal) — NOT the raw line value. This way a
			// paid sale refunds only what was overpaid, and an unpaid credit-sale return refunds nothing (the
			// return just reduces what the customer owes). See the re-settle block for the REFUND tender.
			boolean partial = retQty > 0f && retQty < soldQty;
			java.math.BigDecimal refundedAmount = java.math.BigDecimal.ZERO;   // SF-11: recorded on the return audit
			java.math.BigDecimal storeCreditIssued = java.math.BigDecimal.ZERO;   // SF-5 Model B: overpayment issued as credit
			// GL SALE_RETURN: capture the returned line's ex-tax net, tax and COGS BEFORE the line is adjusted/deleted.
			// Ex-tax net must be the DISCOUNTED net the sale actually posted = netAmount − taxAmount (netAmount is the
			// discounted, tax-inclusive line total). Using totalAmount (qty×rate, PRE-discount) over-reverses Sales+AR
			// by the discount — the same drift fixed in voidSell.
			float retFrac = soldQty > 0f ? (retQty / soldQty) : 1f;
			java.math.BigDecimal retSub = nzbd(existingSell.getNetAmount()).subtract(nzbd(existingSell.getTaxAmount()))
					.multiply(java.math.BigDecimal.valueOf(retFrac)).setScale(2, java.math.RoundingMode.HALF_UP);
			java.math.BigDecimal retTax = nzbd(existingSell.getTaxAmount()).multiply(java.math.BigDecimal.valueOf(retFrac)).setScale(2, java.math.RoundingMode.HALF_UP);
			java.math.BigDecimal retCost = nzbd(existingSell.getCostPrice()).multiply(java.math.BigDecimal.valueOf(retQty)).setScale(2, java.math.RoundingMode.HALF_UP);
			String retInvoiceNo = ch != null ? ch.getInvoiceNo() : null;
			// B2B-P3c (#1): allocate the CREDIT NOTE number here, before either use, so the document row and the
			// GL line carry the SAME number. MAX+1 per org inside this transaction; the ledger line then names
			// the credit note that caused it, and the invoice stays reachable via sale_return.invoice_no.
			long creditNoteSeq = saleReturnRepo.maxCreditNoteSeqForOrg(orgId()) + 1;
			String creditNoteNo = com.myplus.commerce.domain.InvoiceNumbers.creditNote(creditNoteSeq);

			// Adjust the returned line: a full return removes it; a partial return reduces its qty and money
			// pro-rata so the invoice keeps the portion the customer is keeping.
			if (partial) {
				java.math.BigDecimal keepFrac = java.math.BigDecimal.valueOf(soldQty - retQty)
						.divide(java.math.BigDecimal.valueOf(soldQty), 6, java.math.RoundingMode.HALF_UP);
				existingSell.setQuantity(soldQty - retQty);
				existingSell.setTotalAmount(nzbd(existingSell.getTotalAmount()).multiply(keepFrac).setScale(2, java.math.RoundingMode.HALF_UP));
				existingSell.setNetAmount(nzbd(existingSell.getNetAmount()).multiply(keepFrac).setScale(2, java.math.RoundingMode.HALF_UP));
				existingSell.setTaxAmount(nzbd(existingSell.getTaxAmount()).multiply(keepFrac).setScale(2, java.math.RoundingMode.HALF_UP));
				existingSell.setSrp(nzbd(existingSell.getSrp()).multiply(keepFrac).setScale(2, java.math.RoundingMode.HALF_UP));
				existingSell.setUpdated(java.time.LocalDateTime.now());
				sellService.save(existingSell);
			} else {
				sellService.deleteById(dto.getSellId());
			}

			// Re-settle the invoice header on its SURVIVING lines and recompute the customer's running due, so the
			// return is reflected everywhere it is read — the dashboard's "customers with dues" (getDashboardChartData),
			// the customer ledger/statement and the invoice totals. The header stores dueAmount = paidAmount − grandTotal
			// (negative while owing); recomputeDue() sums those headers into Customer.dueAmount.
			if (ch != null && ch.getCustomer_history_id() != null) {
				// B2B-P3f: capture the invoice AS ISSUED before this return re-settles it. Once only — a second
				// return must not overwrite it with an already-netted figure, which would understate the bill on
				// the statement and double-count the first credit note. Falls back to grandTotal, which IS the
				// issued value the first time through. (V34 back-fills rows that were never returned.)
				if (ch.getIssuedTotal() == null)
					ch.setIssuedTotal(nzbd(ch.getGrandTotal()));

				List<Sell> surviving = sellService.findByInvoiceScoped(ch.getCustomer_history_id(), orgId(), userId());
				java.math.BigDecimal subTotal = java.math.BigDecimal.ZERO, taxTotal = java.math.BigDecimal.ZERO;
				for (Sell s : surviving) {
					subTotal = subTotal.add(nzbd(s.getTotalAmount()));
					taxTotal = taxTotal.add(nzbd(s.getTaxAmount()));
				}
				java.math.BigDecimal grandTotal = subTotal.add(taxTotal);
				ch.setSubTotal(subTotal);
				ch.setTaxTotal(taxTotal);
				ch.setGrandTotal(grandTotal);

				// SF-5: reconcile the header payment against the new total. If the return leaves the invoice
				// OVERPAID (paidAmount > grandTotal), refund exactly that overpayment (cash back to the customer,
				// recorded as a negative REFUND tender) and drop paidAmount to what's retained — so dueAmount is
				// never falsely positive and the customer's running balance can't be floored-away. An unpaid /
				// credit-sale return overpays by ≤ 0, so it refunds nothing and simply reduces what is owed.
				java.math.BigDecimal priorPaid = nzbd(ch.getPaidAmount());
				java.math.BigDecimal refund = priorPaid.subtract(grandTotal);   // > 0 only when overpaid
				if (refund.signum() > 0) {
					// SF-5 Model B: refund as CASH (default) or as STORE CREDIT (opt-in) when the customer is known.
					String refundAs = request.getParameter("refundAs");
					Customer cust = ch.getCustomer();
					if ("CREDIT".equalsIgnoreCase(refundAs) && cust != null && cust.getCustomerId() != null) {
						storeCreditService.issue(cust.getCustomerId(), refund, "RETURN", ch.getInvoiceNo());   // +credit, org keeps the cash
						storeCreditIssued = refund;
					} else {
						paymentService.refund(ch.getCustomer_history_id(), refund, orgId(), userId());  // cash back (REFUND tender)
					}
					ch.setPaidAmount(priorPaid.subtract(refund));   // = grandTotal; the returned value leaves paidAmount
					refundedAmount = refund;                        // SF-11: capture for the return audit record
				}
				ch.setDueAmount(nzbd(ch.getPaidAmount()).subtract(grandTotal));   // ≤ 0 (owing or settled)
				ch.setUpdated(java.time.LocalDateTime.now());
				customerHistoryService.save(ch);

				Customer customer = ch.getCustomer();
				if (customer != null)
					customerService.recomputeDue(customer);
			}

			// SF-11: write the return audit / credit-note stub (who/what/why/how-much). Best-effort — the return is
			// already applied, so a logging hiccup must never fail it.
			try {
				com.myplus.business_service.entity.SaleReturn cn = new com.myplus.business_service.entity.SaleReturn();
				cn.setCreditNoteSeq(creditNoteSeq);
				cn.setCreditNoteNo(creditNoteNo);
				cn.setInvoiceNo(ch != null ? ch.getInvoiceNo() : null);
				cn.setSellId(dto.getSellId());
				cn.setProductId(existingSell.getProductId());
				cn.setQuantity(retQty);
				cn.setReason(request.getParameter("reason"));
				cn.setRefundAmount(refundedAmount);
				// B2B-P3f: the credit note's FACE VALUE — the returned goods plus their tax, the same figure the
				// GL reversal posts below. refundAmount beside it is only the CASH handed back, which is zero on
				// a credit sale, so it could never be the document's value. This is what puts the note on the
				// statement; without it the note exists but has nothing to show.
				cn.setCreditAmount(retSub.add(retTax));
				cn.setOrganizationId(orgId());
				cn.setUserId(userId());
				cn.setStoreId(existingSell.getStoreId());   // the return belongs to the store that made the sale
				cn.setDated(java.time.LocalDateTime.now());
				saleReturnRepo.save(cn);
			} catch (Exception auditOnly) {
				LOGGER.warn(this.getClass().getName() + " > saleReturn audit write failed (return applied)", auditOnly);
			}

			// GL: post the SALE_RETURN reversal (best-effort) — reverses Sales/Tax + AR/Cash refund + COGS/Inventory.
			try {
				java.math.BigDecimal retGross = retSub.add(retTax);
				if (retGross.signum() > 0) {
					glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
							.eventType("SALE_RETURN").date(java.time.LocalDate.now()).ref(creditNoteNo)
							.grandTotal(retGross).subTotal(retSub).taxTotal(retTax).cost(retCost).paidAmount(refundedAmount)
							.method("CASH").storeCredit(storeCreditIssued).build());   // credit-issue portion → Cr 2200 (not Cash)
				}
			} catch (Exception glEx) {
				LOGGER.warn(this.getClass().getName() + " > saleReturn GL reversal enqueue failed (return applied)", glEx);
			}

			auditService.record("SALE_RETURN", "INVOICE", retInvoiceNo, retSub.add(retTax), "qty=" + retQty);   // #6
			// B2B-P3c (#1): name the document that was issued. The operator quotes this to the customer, and it
			// is what the credit note is filed under -- returning only "success" leaves them nothing to cite.
			return new GenericResponse("SUCCESS", "Sale returned. Credit note " + creditNoteNo, creditNoteNo);

		} catch (com.myplus.business_service.service.PeriodClosedException pce) {
			LOGGER.warn("saleReturn rejected (period closed): {}", pce.getMessage());
			return new GenericResponse("FAILED", pce.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > saleReturn " + e.getCause(), e);
			return new GenericResponse("FAILED", "An unexpected error occurred. Please contact support.");
		}
	}

	/**
	 * Audit #3: VOID an entire invoice — the books-safe replacement for hard-delete. Reverses every line at full
	 * quantity through the same path a return uses (inventory restore → customer-due recompute → GL SALE_RETURN via
	 * the #4 outbox), refunds any amount paid, then soft-stamps the header VOID (record + history survive) and makes
	 * it read-only. Rejected if already VOID or if any partial return was already recorded (would double-reverse).
	 */
	@Transactional
	@PreAuthorize("hasAuthority('VOID_INVOICE')")
	@PostMapping(value = "/voidSell")
	@ResponseBody
	public GenericResponse voidSell(final HttpServletRequest request) {
		try {
			Long chId = appUtil.isEmptyOrNull(request.getParameter("customerHistoryId")) ? null
					: Long.valueOf(request.getParameter("customerHistoryId"));
			String reason = request.getParameter("reason");
			String invoiceNo = request.getParameter("invoiceNo");
			// Resolve by invoiceNo when the id isn't supplied (API ergonomics — the caller often only has the number).
			if (chId == null && !appUtil.isEmptyOrNull(invoiceNo))
				chId = customerHistoryService.findByOrgAndInvoiceNo(orgId(), invoiceNo).map(CustomerHistory::getCustomer_history_id).orElse(null);
			if (chId == null)
				return new GenericResponse("NOT_FOUND", "No invoice id provided.");

			CustomerHistory ch = customerHistoryService.findById(chId).orElse(null);
			if (ch == null || !inMyTenant(ch.getOrganizationId(), ch.getUserId()) || !myStore(ch.getStoreId()))
				return new GenericResponse("NOT_FOUND", "Invoice not found.");   // anti-IDOR (org + store)
			// The reversal mechanics live in SaleVoidService so the storefront-cancel path (OMS O1) reverses an
			// invoice the SAME way rather than growing a second one. Resolution, tenant/store scoping and the
			// VOID_INVOICE privilege stay here — they are this endpoint's trust rules, not properties of voiding.
			boolean quarantine = "true".equalsIgnoreCase(request.getParameter("quarantine"));   // P11: pharmacy no-restock
			saleVoidService.voidInvoice(ch, reason, quarantine, orgId(), userId());
			return new GenericResponse("SUCCESS", "Invoice voided.");
		} catch (com.myplus.business_service.service.SaleVoidService.VoidRefused refused) {
			return new GenericResponse("FAILED", refused.getMessage());
		} catch (com.myplus.business_service.service.PeriodClosedException pce) {
			LOGGER.warn("voidSell rejected (period closed): {}", pce.getMessage());
			return new GenericResponse("FAILED", pce.getMessage());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > voidSell " + e.getCause(), e);
			return new GenericResponse("FAILED", "An unexpected error occurred. Please contact support.");
		}
	}

	/** SF-11: the sale-return / credit-note audit log for this tenant (newest first), for review + a future
	 *  printable credit note. Flat records — no lazy relations. Org-scoped with NULL-fallback. */
	@RequestMapping(value = "/getSaleReturns", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getSaleReturns(final HttpServletRequest request) {
		try {
			return new GenericResponse("SUCCESS", "Sale returns loaded", saleReturnRepo.findScoped(orgId(), userId()));
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getSaleReturns " + e.getMessage(), e);
			return new GenericResponse("FAILED", "Could not load sale returns.");
		}
	}
}
