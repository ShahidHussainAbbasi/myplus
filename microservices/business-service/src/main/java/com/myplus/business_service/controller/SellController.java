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
import com.myplus.business_service.dto.ReturnDocumentDTO;
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
	@Autowired private com.myplus.business_service.service.DocumentNumberService documentNumberService;
	
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
	com.myplus.business_service.service.InstallmentPlanService installmentPlanService;   // INST-1

	/**
	 * C3 — what this tenant is allowed to do. Guards the installment write below.
	 *
	 * <p><b>REQUIRED, deliberately</b> — this was written as {@code required = false} first, which is exactly
	 * how OMS O3 shipped a settings resolver that silently did nothing: catalog, migration and resolver all
	 * present, no {@code SettingsStore}, optional injection, so every tenant quietly kept the platform default
	 * and nothing anywhere said so. A guard that disables itself when a bean is missing is worse than no guard,
	 * because it reads as protection.
	 *
	 * <p>business-service ships a {@code SettingsStore} and serves the Configuration screen from it — the very
	 * condition the auto-configuration keys on. If this cannot be satisfied, the right outcome is a service
	 * that refuses to start and says why.
	 */
	@Autowired
	com.myplus.common.settings.CapabilityService capabilityService;                      // C3

	@Autowired
	com.myplus.business_service.service.SaleCosting saleCosting;                         // #17 P3

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
			// Optional override for "Booked By". Blank (the default) means the receipt keeps using the
			// per-sale stamped name, so leaving it alone changes nothing for any existing tenant.
			lh.setBookedBy(settingsService.getText("pos.document.bookedBy"));

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
	/**
	 * UI/UX P3 — the shop's best-selling products, for the POS quick-pick tiles.
	 *
	 * <p>Goods with no barcode (produce, bakery, services) fall back to the slow item form on every sale;
	 * the tiles give them scan-path speed. Org-scoped and store-aware in the service, so a shared till
	 * shows every cashier the same grid.
	 *
	 * <p>A read of the caller's own sales history — no extra privilege needed beyond reaching this
	 * service, and nothing here can be widened by a crafted parameter: {@code days} and {@code limit}
	 * are clamped, and the tenant comes from the token, never the request.
	 */
	@RequestMapping(value = "/topProducts", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse topProducts(@RequestParam(required = false) Integer days,
	                                   @RequestParam(required = false) Integer limit) {
		try {
			// (status, collection) — GenericResponse carries lists in `collection`, never `data`, so the
			// client reads resp.collection. Getting the argument order backwards is a compile error here,
			// which is the only reason it isn't a silent shape change.
			return new GenericResponse("SUCCESS", sellService.topProducts(
					days == null ? 30 : days, limit == null ? 9 : limit));
		} catch (Exception e) {
			LOGGER.error("topProducts failed", e);
			// The tiles are an accelerator, never a gate: an empty grid still leaves every product
			// reachable through the normal picker.
			return new GenericResponse("SUCCESS", java.util.Collections.<Object>emptyList());
		}
	}

	/**
	 * U3 - everything the till needs to offer a product by the piece.
	 *
	 * <pre>
	 *   GET /looseInfo?productId=88
	 *   -> { allowLoose, packSize, looseUnit, looseUnitPlural, looseRate, packRate }
	 * </pre>
	 *
	 * <h3>Why this exists rather than the browser doing the arithmetic</h3>
	 *
	 * The sale screen shows the per-piece price LIVE, before the line is committed - that hint line is the
	 * feature, because it is what stops the cashier doing arithmetic at the counter. The number has to come
	 * from somewhere, and there were three candidates:
	 *
	 * <ul>
	 *   <li><b>Recompute in JavaScript</b> - rejected. It is a second implementation of the CEILING rule and
	 *       the markup, and it drifts from this one the day either changes. The visible symptom is a shop
	 *       quoting one price on screen and charging another on the receipt.</li>
	 *   <li><b>Call the server per keystroke</b> - rejected. A remote call on the hot path, per character.</li>
	 *   <li><b>Fetch the RATE once per product, multiply in the browser</b> - chosen. The browser does
	 *       {@code pieces x rate}, which is a multiplication, not a pricing rule.</li>
	 * </ul>
	 *
	 * <p>Called once when a product is picked, alongside the calls the screen already makes, and cached per
	 * product for the rest of the session.
	 *
	 * <p><b>Advisory, and the till must present it that way.</b> For a customer on a contract price the sale
	 * path derives the loose rate from THAT price, which this endpoint does not know - so the hint may differ
	 * from the final line. That is already true of pack lines, whose rate the quote adjusts after the line is
	 * added. {@link SagaSellService#looseLine} at submit remains authoritative.
	 */
	@RequestMapping(value = "/looseInfo", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse looseInfo(@RequestParam Long productId) {
		try {
			com.myplus.commerce.contracts.dto.ProductRef p = catalogClient.getProduct(productId);
			java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
			boolean loose = p != null && Boolean.TRUE.equals(p.getAllowLoose())
					&& p.getPackSize() != null && p.getPackSize() > 1;
			out.put("allowLoose", loose);
			if (!loose) {
				// An ordinary product answers plainly. The till hides its unit toggle on this, so the
				// commonest sale screen in the country looks exactly as it does today.
				return new GenericResponse("SUCCESS", "", out);
			}
			java.math.BigDecimal packRate = p.getSellingPrice() != null
					? p.getSellingPrice() : java.math.BigDecimal.ZERO;
			java.math.BigDecimal markup = settingsService.getDecimal("pos.sale.looseMarkupPct",
					java.math.BigDecimal.ZERO);
			out.put("packSize", p.getPackSize());
			out.put("looseUnit", p.getLooseUnit());
			out.put("looseUnitPlural", p.getLooseUnitPlural());
			out.put("packRate", packRate);
			out.put("looseRate", com.myplus.business_service.service.SagaSellService.looseRateOf(packRate, p.getPackSize(), markup));
			return new GenericResponse("SUCCESS", "", out);
		} catch (Exception e) {
			LOGGER.error("looseInfo failed for product {}", productId, e);
			// A failure here must NOT block the sale: the till falls back to pack-only, which is what it
			// does today. Losing a hint is a degraded screen; refusing the line would be a stopped counter.
			return new GenericResponse("ERROR", "Could not read the pack rules for this product.");
		}
	}

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
						/*
						 * U4 - the NOUN a receipt prints: "5 tablets", not "5".
						 *
						 * The line stores soldUnit/soldQuantity/soldRate/packSizeSnapshot, but not what a
						 * piece is CALLED - so a stored loose line read back could only say "5".
						 *
						 * Derived on read from the product, deliberately, and NOT stamped on the row:
						 * itemName, sku and description on the two lines above are already derived exactly
						 * this way, so a product rename already changes what an old receipt says. Making the
						 * unit noun stricter than the product NAME would be inconsistent, and two extra
						 * varchars on the highest-volume table in the system is a real cost for a purely
						 * cosmetic fidelity gain.
						 *
						 * The QUANTITY is a different matter and is frozen on the row (packSizeSnapshot,
						 * U2 3.2) - because a wrong number is wrong, while a renamed noun is merely dated.
						 */
						dto.setLooseUnit(p.getLooseUnit());
						dto.setLooseUnitPlural(p.getLooseUnitPlural());
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
										b.getBatchNo(), b.getExpiryDate(), b.getQuantity(), b.getUnitCost()));
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
			out.setShippingFee(ch.getShippingFee());         // V39: delivery, added after tax — must print
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
										b.getBatchNo(), b.getExpiryDate(), b.getQuantity(), b.getUnitCost()));
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

	        /*
	         * WHICH PERIOD, decided once and safely.
	         *
	         * `rp` is an Integer, and this used to read `dto.getRp() == CURRENT_MONTH` — an Integer compared to
	         * an int, which UNBOXES. A request that carried no `rp` at all therefore threw a
	         * NullPointerException, which the catch below turned into a bare "could not load" with nothing to
	         * act on.
	         *
	         * The second failure was quieter and worse: a period with no dates matched NO branch, so `objs`
	         * stayed null and the caller got NOT_FOUND — "you have no sales" — when the truth was "you did not
	         * tell me when". A report that answers a malformed question with an empty result teaches an
	         * operator that their data is missing.
	         *
	         * So: absent or unparseable period means CURRENT MONTH, which is what the screen shows selected by
	         * default. The report now answers the question the screen appears to be asking.
	         */
	        Integer rp = dto.getRp();
	        boolean noRange = appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd());
	        boolean currentMonth = (rp == null) ? noRange : (rp.intValue() == CURRENT_MONTH);

	        if(currentMonth) {
	        	objs = sellService.findSellByDates(appUtil.firstDateTimeOfMonth(),appUtil.lastDateTimeOfMonth(), user.getOrganizationId(), user.getUserId());
	        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
	        	// The end date is INCLUSIVE of its day — see AppUtil.endOfDay. Without this, picking the same day
	        	// for both ends returned nothing at all.
	        	objs = sellService.findSellByDates(appUtil.getDateTime(dto.getSd()), appUtil.endOfDay(appUtil.getDateTime(dto.getEd())), user.getOrganizationId(), user.getUserId());
	        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByStartDate(appUtil.getDateTime(dto.getSd()), user.getOrganizationId(), user.getUserId());
	        }else if(appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByEndDate(appUtil.endOfDay(appUtil.getDateTime(dto.getEd())), user.getOrganizationId(), user.getUserId());
	        }

	        if(objs == null) {
	            // Nothing matched — a period was named but no usable range came with it. Fall back to the
	            // month rather than reporting an empty shop, and say so in the message so the operator knows
	            // WHICH period they are looking at.
	            objs = sellService.findSellByDates(appUtil.firstDateTimeOfMonth(),
	                    appUtil.lastDateTimeOfMonth(), user.getOrganizationId(), user.getUserId());
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
					dtotemp.setManufacturer(p.getManufacturer());   // #18: report dimension (company/brand)
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
							.manufacturer(dto.getManufacturer())   // #18
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
				String groupedCsv = com.myplus.common.imports.CsvWriter.write(
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
			String csv = com.myplus.common.imports.CsvWriter.write(
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

			// INST-5a — the serial is checked BEFORE the sale is written, not while the plan is created.
			// SagaSellService commits the invoice in its own REQUIRES_NEW transaction, so a refusal raised
			// during plan creation arrives after the handset is already sold. A serial financed to somebody
			// else is not a technical hiccup the sale should survive — it is a sale that should not happen.
			if (dto.getInstallmentPlan() != null) {
				/*
				 * C3 — the tenant must actually HAVE installments before one can be sold.
				 *
				 * Hiding the plan block on the sale screen is not what stops this: the screen is the client's
				 * copy and the endpoint answered whoever posted to it. This is the refusal, and it sits here
				 * for the same reason the serial check below does — BEFORE anything is written, so a tenant
				 * without the capability gets a refused sale rather than a committed invoice with no plan
				 * against it.
				 *
				 * assertEnabled fails CLOSED (no tenant on the request => refuse), which is right for a write
				 * that creates a receivable schedule. The rendering side fails open; money does not.
				 */
				try {
					capabilityService.assertEnabled(com.myplus.common.settings.Capability.INSTALLMENTS);
				} catch (RuntimeException notAllowed) {
					// Caught rather than propagated because this endpoint answers in the monolith's
					// GenericResponse envelope, which the sale screen reads. Letting it reach the global
					// handler would return the service's ApiResponse shape and the cashier would see a
					// generic failure instead of the reason.
					return new GenericResponse("FAILED", notAllowed.getMessage());
				}
				String serialProblem = installmentPlanService.validateSerial(
						orgId(), dto.getInstallmentPlan().getAssetRef());
				if (serialProblem != null) return new GenericResponse("FAILED", serialProblem);

				/*
				 * THE DEPOSIT, CHECKED BEFORE ANYTHING IS WRITTEN.
				 *
				 * A sale that completes WITHOUT the plan it was sold under is worse than no sale at all:
				 * the customer leaves with the handset on terms while the books hold a small cash sale and
				 * a large unexplained balance, and nobody reconciles that from a message. The plan-creation
				 * guard further down still exists as a backstop, but by the time it runs the invoice is
				 * committed and the only honest thing left is to report the split.
				 *
				 * Here nothing has been written yet, so the whole sale can simply be refused — the same
				 * shape as the serial check above, and for the same reason.
				 *
				 * Compared against the TENDERS, not `paidAmount`: tenders are what actually settle an
				 * invoice, which a fixture of mine learned the hard way by asserting against a figure the
				 * server never used.
				 */
				java.math.BigDecimal down = dto.getInstallmentPlan().getDownPayment() == null
						? java.math.BigDecimal.ZERO : dto.getInstallmentPlan().getDownPayment();
				if (down.signum() > 0) {
					java.math.BigDecimal tendered = java.math.BigDecimal.ZERO;
					if (dto.getTenders() != null) {
						for (com.myplus.business_service.dto.TenderDTO t : dto.getTenders()) {
							if (t != null && t.getAmount() != null) tendered = tendered.add(t.getAmount());
						}
					}
					if (down.compareTo(tendered) > 0) {
						return new GenericResponse("FAILED", "The down payment is " + down.toPlainString()
								+ " but only " + tendered.toPlainString() + " is being received. "
								+ "Take the deposit before completing the sale.");
					}
				}
			}

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
			// INST-1 — a sale sold on terms carries a plan block. Written AFTER the sale, deliberately:
			// SagaSellService commits the invoice in its own REQUIRES_NEW transaction, so by the time it
			// returns an invoice number the receivable is durable and the plan can reference it.
			//
			// A plan is a STRUCTURE OVER that receivable, never a second one — no GL account, no posting
			// event, no gl_outbox column. The invoice already carries the full financed amount as AR.
			if (dto.getInstallmentPlan() != null && invoiceNo != null) {
				String planMsg = createInstallmentPlan(dto, invoiceNo);
				if (planMsg != null) msg = msg + "  " + planMsg;
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
			/*
			 * U6 §4 #3 — a sealed pack cannot be given back as loose pieces.
			 *
			 * A return here is an EDIT: the line is reduced and the difference becomes a credit note. So a
			 * line originally sold as a PACK, re-submitted as LOOSE, is a refund of pieces against goods
			 * bought sealed - and the arithmetic favours the customer every time:
			 *
			 *     buy a sealed pack of 10           pay        120.00
			 *     hand back 7 tablets "loose"       refund   7 x 13.20 = 92.40
			 *     ...keeping 3 tablets for 27.60, which the shop itself sells for 39.60.
			 *
			 * The gap is the shop's loss, on every such transaction, and nothing on the invoice looks wrong.
			 * A shop that WANTS to accept it still can - as a fresh sale of the remainder, priced deliberately.
			 *
			 * Deliberately strict: correcting a genuinely mis-keyed unit means voiding and re-ringing. On a
			 * path that moves money out of the till, refusing an ambiguous edit is the safe default.
			 */
			if (dto.getSales() != null) {
				for (SellDTO line : dto.getSales()) {
					if (line == null || line.getProductId() == null) continue;
					if (!"LOOSE".equalsIgnoreCase(String.valueOf(line.getSoldUnit()))) continue;
					for (Sell prior : oldLines) {
						if (!line.getProductId().equals(prior.getProductId())) continue;
						if (!"LOOSE".equalsIgnoreCase(String.valueOf(prior.getSoldUnit()))) {
							throw new com.myplus.common.web.exception.ValidationException(
									"This line was sold as a whole pack, so it cannot be returned by the piece. "
									+ "Take the pack back, or sell the remainder as a new sale.");
						}
					}
				}
			}
			/*
			 * U6 ⭐ — DERIVE `quantity` FOR A LOOSE LINE BEFORE THE DELTA IS COMPUTED.
			 *
			 * The delta below reads `s.getQuantity()` off the RAW DTO, and `SellDTO.quantity` DEFAULTS TO 1.
			 * So a loose line carrying only `soldQuantity` contributed −1 PACK instead of −0.2:
			 *
			 *     old 0.5  −  new 1.0  =  −0.5     the edit TOOK another half pack
			 *     on-hand  9.5 → 9.0              instead of 9.5 → 9.8
			 *
			 * The invoice money was right the whole time — buildLines re-prices correctly — so ONLY THE SHELF
			 * WAS WRONG, which is the kind of error a shop finds weeks later at a stock count.
			 *
			 * <h3>Why it was invisible</h3>
			 * U3's browser code sets `quantity` before posting, so from the sale screen the DTO arrives
			 * already converted. The server was therefore TRUSTING THE BROWSER to do a conversion it had
			 * promised to do itself: U2 states that "the server ignores quantity on a LOOSE line and derives
			 * it, so a browser that gets the conversion wrong cannot mis-sell". True in addSell. It was not
			 * true here, and nothing said so — it worked from the screen and was wrong from every other caller.
			 *
			 * <h3>Which pack size</h3>
			 * `packSizeSnapshot` FROM THE LINE BEING RETURNED — the size in force when it was sold. Using the
			 * product's current value would mis-restock every historical return the day a shop changes its
			 * pack size, which is exactly why U2 froze it on the line. The product is consulted only for a
			 * loose line newly ADDED during an edit, which has no prior to inherit from.
			 */
			if (dto.getSales() != null) {
				for (SellDTO line : dto.getSales()) {
					if (line == null || line.getProductId() == null) continue;
					if (!"LOOSE".equalsIgnoreCase(String.valueOf(line.getSoldUnit()))) continue;
					Float pieces = line.getSoldQuantity();
					if (pieces == null || pieces <= 0f) continue;   // buildLines refuses these, with a reason

					Integer packSize = null;
					for (Sell prior : oldLines) {
						if (line.getProductId().equals(prior.getProductId())
								&& prior.getPackSizeSnapshot() != null) {
							packSize = prior.getPackSizeSnapshot();
							break;
						}
					}
					if (packSize == null) {
						try {
							com.myplus.commerce.contracts.dto.ProductRef ref =
									catalogClient.getProduct(line.getProductId());
							if (ref != null) packSize = ref.getPackSize();
						} catch (Exception catalogUnavailable) {
							LOGGER.warn("U6: could not read pack size for product {}; the stock delta for this "
									+ "loose line may be wrong", line.getProductId(), catalogUnavailable);
						}
					}
					if (packSize != null && packSize > 1) {
						line.setQuantity(pieces / (float) packSize);
					}
				}
			}
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
			/*
			 * U10 ⭐ — RETURN TO THE BATCH THE SALE TOOK FROM.
			 *
			 * There are two ways stock comes back, and only one of them was batch-aware:
			 *
			 *   the dedicated return   -> inventoryClient.returnStock(reservationId, ...)
			 *                             ReservationService.returnPicks restores each unit to its ORIGINAL
			 *                             batch, capped per pick, keeping the real expiry so FEFO stays right
			 *
			 *   an EDIT of the invoice -> importStock(bare line, no batch)
			 *                             a FRESH StockEntry with no lot and no expiry
			 *
			 * And an edit is how a loose return happens (U6): the cashier reduces the line and the difference
			 * becomes a credit note. So every returned tablet re-entered stock as an untraceable, undated
			 * entry — the quantity right, the LOT WRONG. FEFO would then sell the returned units after the
			 * near-dated batch they actually came from, and a recall could not find them.
			 *
			 * Routing an edit's return through the same reservation makes both paths identical. Inventory
			 * already caps per pick, so an edit that returns more than this reservation took is handled there
			 * rather than guessed at here.
			 */
			if (!returnLines.isEmpty() && ch.getReservationId() != null && !ch.getReservationId().isBlank()) {
				java.util.List<com.myplus.commerce.contracts.dto.StockReturnLine> byBatch =
						new java.util.ArrayList<>();
				for (com.myplus.commerce.contracts.dto.StockImportLine l : returnLines) {
					// StockReturnLine.qty is Float (the contract inconsistency U0 2.3 recorded and left alone).
					byBatch.add(new com.myplus.commerce.contracts.dto.StockReturnLine(
							l.getProductId(), l.getQuantity()));
				}
				try {
					// The 1-arg form is a plain restock (quarantine=false) — an edit that reduces a line is a
					// customer changing their mind, not a clinical return, so the goods go back on the shelf.
					inventoryClient.returnStock(ch.getReservationId(),
							new com.myplus.commerce.contracts.dto.StockReturnRequest(byBatch));
					returnLines.clear();   // handled by the reservation; do not import a second, batchless copy
				} catch (Exception batchReturnFailed) {
					// Fall through to the flat import below rather than lose the stock. A returned unit in the
					// wrong batch is a traceability problem; a returned unit in NO batch is a missing one.
					LOGGER.warn("Batch-aware return failed for reservation {}; falling back to a flat restock",
							ch.getReservationId(), batchReturnFailed);
				}
			}
			if (!takeLines.isEmpty()) {
				com.myplus.commerce.contracts.dto.StockReservationResponse resp = inventoryClient.reserve(
						new com.myplus.commerce.contracts.dto.StockReservationRequest(java.util.UUID.randomUUID().toString(), takeLines));
				if (resp == null || resp.getStatus() != com.myplus.commerce.contracts.dto.ReservationStatus.RESERVED)
					return new GenericResponse("ERROR", "Not enough stock to apply this change.");
				// OMS O5a: compensate a failed confirm. This used to be a bare confirm() - if it threw, the hold was
				// stranded with nothing even attempting to free it, and because availability is
				// (quantity - reservedQuantity) that stock became permanently unsellable. O5a's sweeper catches this
				// ~30 minutes later; a safety net is not a reason to drop things.
			try {
					inventoryClient.confirm(resp.getReservationId());
				} catch (RuntimeException confirmFailed) {
					try {
						inventoryClient.release(resp.getReservationId());
					} catch (RuntimeException releaseFailed) {
						LOGGER.warn("Sale edit: confirm AND compensating release both failed for reservation {}; "
								+ "the expiry sweeper will return the stock", resp.getReservationId(), releaseFailed);
					}
					throw confirmFailed;
				}
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
			// V39: the whole-document legs of the posting being reversed. The reversal has to mirror what the
			// sale actually posted — Sales was credited at the GROSS goods value with the concession debited to
			// 4200, and delivery was credited to 4300 — so a reversal that omits them leaves both accounts
			// holding a stale balance and, because delivery sits inside grandTotal, does not balance at all.
			java.math.BigDecimal oldDiscount = nzbd(ch.getTradeDiscount()), oldShipping = nzbd(ch.getShippingFee());
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
				/*
				 * #17 P3 — ONE cost definition. The edit reposts what the sale actually cost, from the
				 * batches it consumed; SaleCosting falls back to the line snapshot for a sale written
				 * before P3, so a historical edit still reposts the figure it originally posted.
				 */
				java.math.BigDecimal newCost = saleCosting.cogs(lines,
						sellService.findByInvoiceScoped(ch.getCustomer_history_id(), orgId(), userId())
								.stream().map(Sell::getSellId).filter(java.util.Objects::nonNull)
								.collect(java.util.stream.Collectors.toList()));
				String mode = ch.getPaymentMode();
				if (oldGrand.signum() > 0)
					glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
							.eventType("SALE_RETURN").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
							.grandTotal(oldGrand).subTotal(oldSub).taxTotal(oldTax).cost(oldCost).paidAmount(oldPaid)
							.discountTotal(oldDiscount).shippingFee(oldShipping)   // reverse what the sale posted
							.method(mode).build());
				glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
						.eventType("SALE").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
						.grandTotal(nzbd(ch.getGrandTotal())).subTotal(nzbd(ch.getSubTotal())).taxTotal(nzbd(ch.getTaxTotal()))
						.discountTotal(nzbd(ch.getTradeDiscount())).shippingFee(nzbd(ch.getShippingFee()))
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
			/*
			 * #17 P3 — the returned goods are credited at what they COST WHEN THEY LEFT, allocated from the
			 * batch cost recorded on the sale, not recomputed from a current rate. Returning three of eleven
			 * units must reverse three units of the original cost; anything else moves margin on a sale that
			 * already happened.
			 *
			 * Falls back to the line snapshot (the pre-P3 formula) when the sale recorded no batch costs.
			 */
			java.math.BigDecimal retCost = saleCosting.cogsForPortionOfSell(
					existingSell.getSellId(), java.math.BigDecimal.valueOf(retQty),
					java.math.BigDecimal.valueOf(soldQty), nzbd(existingSell.getCostPrice()));
			String retInvoiceNo = ch != null ? ch.getInvoiceNo() : null;
			// B2B-P3c (#1): allocate the CREDIT NOTE number here, before either use, so the document row and the
			// GL line carry the SAME number. MAX+1 per org inside this transaction; the ledger line then names
			// the credit note that caused it, and the invoice stays reachable via sale_return.invoice_no.
			// Allocated from the serialised counter, NOT MAX+1: this runs AFTER the inventory return, so a
			// collision here could not be retried without putting the stock back twice. Allocated LATE — the
			// row lock is held from here until commit.
			long creditNoteSeq = documentNumberService.next(orgId(),
					com.myplus.business_service.service.DocumentNumberService.CREDIT_NOTE);
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
				// V39 — the two whole-document figures must survive a PARTIAL return, each in its own way.
				// Recomputing the header from the surviving lines alone silently dropped both: the customer
				// stopped owing a delivery fee for goods that HAD been delivered, and a concession they were
				// granted simply disappeared, quietly increasing what they owed.
				//
				//  • DELIVERY is retained in full. The van went out; a shop that refunds the carriage because
				//    one of six items came back is paying to be returned to.
				//  • The CONCESSION follows the goods, pro-rata — the same rule the line adjustment above uses.
				//    Keeping it whole would over-credit a customer who returned most of the order; dropping it
				//    would charge them list price for goods they were given a discount on.
				java.math.BigDecimal priorDiscount = nzbd(ch.getTradeDiscount());
				java.math.BigDecimal shippingKept  = nzbd(ch.getShippingFee());
				java.math.BigDecimal keptDiscount  = java.math.BigDecimal.ZERO;
				if (priorDiscount.signum() > 0) {
					// Gross goods BEFORE this return: the header's subTotal is already net of the concession.
					java.math.BigDecimal priorGross = nzbd(ch.getSubTotal()).add(priorDiscount);
					keptDiscount = priorGross.signum() == 0 ? java.math.BigDecimal.ZERO
							: priorDiscount.multiply(subTotal).divide(priorGross, 2, java.math.RoundingMode.HALF_UP);
					if (keptDiscount.compareTo(subTotal) > 0) keptDiscount = subTotal;   // never a negative invoice
				}

				java.math.BigDecimal grandTotal = subTotal.subtract(keptDiscount).add(taxTotal).add(shippingKept);
				ch.setSubTotal(subTotal.subtract(keptDiscount));
				ch.setTaxTotal(taxTotal);
				ch.setGrandTotal(grandTotal);
				ch.setTradeDiscount(keptDiscount.signum() > 0 ? keptDiscount : null);

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
	public GenericResponse getSaleReturns(
			@RequestParam(name = "customerId", required = false) final Long fCustomer,
			@RequestParam(name = "productId", required = false) final Long fProduct,
			@RequestParam(name = "from", required = false) final String fromStr,
			@RequestParam(name = "to", required = false) final String toStr,
			final HttpServletRequest request) {
		try {
			/*
			 * Task #21: returned as the SAME ReturnDocumentDTO the printable note uses, not as raw rows.
			 *
			 * A raw SaleReturn carries a productId and no customer at all, so a register built from it would
			 * be a table of ids — technically a successful response and useless to the person reading it.
			 * Sharing the document's shape also means the screen reads one set of field names for both the
			 * list and the note, instead of a second vocabulary that drifts from the first.
			 *
			 * BATCHED, not per row: two lookups for the whole page regardless of its length. Resolving names
			 * inside the loop would be an N+1 on a screen that lists a shop's entire return history.
			 */
			/*
			 * #24 — the register's filters.
			 *
			 * Date and product narrow in SQL because they are columns on the row. The CUSTOMER cannot:
			 * SaleReturn records no customer at all, so it is applied in memory further down, after the
			 * enrichment that resolves one. See docs/slices/returns-register-parity.md §3.
			 *
			 * ⚠ endOfDay on the upper bound. The picker sends a date as midnight, so a same-day range read
			 * literally is 00:00:00..00:00:00 and matches only a return recorded at exactly midnight — the
			 * report-date-bounds defect, which is reproduced by every new date filter that parses its own
			 * bounds instead of using this helper.
			 */
			java.time.LocalDate fromD = appUtil.toLocalDateOrNull(fromStr);
			java.time.LocalDate toD = appUtil.toLocalDateOrNull(toStr);
			java.time.LocalDateTime from = fromD == null ? null : fromD.atStartOfDay();
			java.time.LocalDateTime to = toD == null ? null : appUtil.endOfDay(toD.atStartOfDay());

			java.util.List<com.myplus.business_service.entity.SaleReturn> rows =
					saleReturnRepo.findScopedFiltered(orgId(), userId(), fProduct, from, to);

			java.util.Map<Long, com.myplus.commerce.contracts.dto.ProductRef> productById = productRefs(
					rows.stream().map(com.myplus.business_service.entity.SaleReturn::getProductId)
							.filter(java.util.Objects::nonNull).distinct()
							.collect(java.util.stream.Collectors.toList()));

			// The customer is not on the return — it lives on the original sale. One batched read, then a
			// sellId -> name map.
			java.util.List<Long> sellIds = rows.stream()
					.map(com.myplus.business_service.entity.SaleReturn::getSellId)
					.filter(java.util.Objects::nonNull).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, String> customerBySellId = new java.util.HashMap<>();
			// #24: the ID as well as the name. Filtering on the NAME would fold two customers who happen to
			// share one into a single filter result — a register quietly showing someone else's returns is
			// worse than no filter at all.
			java.util.Map<Long, Long> customerIdBySellId = new java.util.HashMap<>();
			java.util.Map<Long, java.math.BigDecimal> rateBySellId = new java.util.HashMap<>();
			if (!sellIds.isEmpty()) {
				sellService.findAllById(sellIds).forEach(s -> {
					rateBySellId.put(s.getSellId(), s.getSellRate());
					if (s.getCustomerHistory() != null && s.getCustomerHistory().getCustomer() != null) {
						customerBySellId.put(s.getSellId(), s.getCustomerHistory().getCustomer().getName());
						customerIdBySellId.put(s.getSellId(),
								s.getCustomerHistory().getCustomer().getCustomerId());
					}
				});
			}

			/*
			 * The CUSTOMER filter, applied here because this is the first point at which a customer is known.
			 *
			 * ⚠ This narrows AFTER a full scoped read, so it does not scale — a distributor with years of
			 * returns pays for every row before any are discarded. It is the deliberate choice for today's
			 * volumes (and matches how SaleReportFilter narrows the sale report); the right answer once that
			 * hurts is a join through Sell in findScopedFiltered. Recorded so whoever hits the wall finds the
			 * reason rather than rediscovering it.
			 */
			if (fCustomer != null) {
				final Long want = fCustomer;
				rows = rows.stream()
						.filter(r -> want.equals(customerIdBySellId.get(r.getSellId())))
						.collect(java.util.stream.Collectors.toList());
			}

			java.util.List<ReturnDocumentDTO> out = rows.stream()
					.map(r -> toCreditNoteDto(r, productById.get(r.getProductId()),
							customerBySellId.get(r.getSellId()), rateBySellId.get(r.getSellId())))
					.collect(java.util.stream.Collectors.toList());

			return new GenericResponse("SUCCESS", "Sale returns loaded", out);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getSaleReturns " + e.getMessage(), e);
			return new GenericResponse("FAILED", "Could not load sale returns.");
		}
	}

	/**
	 * Task #15/#21 — one {@code SaleReturn} row, resolved into the credit-note shape.
	 *
	 * <p>Defined once and used by BOTH the single-note read and the register, so the list and the printed
	 * document can never disagree about what a return says. The resolved values are passed IN rather than
	 * looked up here: the register resolves them in batch for a whole page, and a helper that fetched its own
	 * would silently turn that into an N+1.
	 *
	 * <p>Amount is {@code creditAmount} — the FACE VALUE (goods + tax) — and never {@code refundAmount}, which
	 * is only the cash handed back and is zero on a credit sale. Null on returns that predate V34, where the
	 * value is genuinely unrecoverable; the register shows those rows with no amount rather than inventing a
	 * zero, and the document read refuses to print them at all.
	 */
	private ReturnDocumentDTO toCreditNoteDto(com.myplus.business_service.entity.SaleReturn r,
			com.myplus.commerce.contracts.dto.ProductRef p, String customerName, java.math.BigDecimal rate) {
		return ReturnDocumentDTO.builder()
				.documentType("CREDIT_NOTE")
				.documentNo(r.getCreditNoteNo())
				.referenceNo(r.getInvoiceNo())
				.dated(r.getDated() != null ? r.getDated().toLocalDate().toString() : null)
				.partyName(customerName)
				.reason(r.getReason())
				.totalAmount(r.getCreditAmount())
				.refundedCash(r.getRefundAmount())
				.storeId(r.getStoreId())
				.lines(java.util.List.of(ReturnDocumentDTO.Line.builder()
						.productId(r.getProductId())
						.productName(p != null ? p.getName() : null)
						.sku(p != null ? p.getSku() : null)
						.quantity(r.getQuantity() != null ? java.math.BigDecimal.valueOf(r.getQuantity()) : null)
						.rate(rate)
						.amount(r.getCreditAmount())
						.build()))
				.build();
	}

	/**
	 * Task #15 — ONE credit note, assembled into something a document can draw.
	 *
	 * <p>This is the "future printable credit note" {@link #getSaleReturns} above anticipated. The row on its
	 * own is not printable: it holds a {@code productId} but no product name, and no customer at all — the
	 * customer lives on the original {@code Sell} → {@code CustomerHistory}. So the work here is resolving what
	 * the row only points at, which is why a repository method could not have answered this.
	 *
	 * <p><b>Keyed on the note NUMBER</b>, which is the document's identity — what the operator is shown after
	 * taking a return, and what a customer quotes back. It is also the only key the caller has: the return
	 * dialog is told "Credit note CRN-000007", not a row id.
	 *
	 * <p><b>Anti-IDOR</b> is the SCOPE PREDICATE, not the choice of key. A row id would be no harder to guess
	 * than {@code CRN-000007}; what makes another tenant's note unreachable is that the org/user filter lives
	 * inside the query, plus the per-record store re-check below — the list queries filter by store, but this
	 * endpoint takes its key from the client, so without it an admin at Store B could read a Store-A note.
	 *
	 * <p><b>⚠ Refuses to print a value it cannot recover.</b> The document's face value is {@code creditAmount}
	 * (returned goods + tax), never {@code refundAmount} — refundAmount is only the cash handed back and is
	 * zero on a credit sale. A null creditAmount means the return predates V34 and its value is genuinely
	 * unrecoverable (a full return deleted the sell row). This REFUSES rather than printing 0.00 onto a
	 * customer-facing note, which is the same call the statement already makes: it omits the line instead of
	 * inventing a number.
	 */
	@RequestMapping(value = "/creditNote", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse creditNote(@RequestParam(name = "no") final String noteNo,
			final HttpServletRequest request) {
		try {
			if (appUtil.isEmptyOrNull(noteNo)) return new GenericResponse("NOT_FOUND");

			com.myplus.business_service.entity.SaleReturn r =
					saleReturnRepo.findByCreditNoteNoScoped(noteNo.trim(), orgId(), userId()).orElse(null);
			// One combined not-found for "absent" and "not yours": distinguishing them would tell a prober
			// which notes exist in other tenants, which is the whole thing the scoped query is closing.
			if (r == null || !myStore(r.getStoreId()))
				return new GenericResponse("NOT_FOUND");

			if (r.getCreditAmount() == null)
				return new GenericResponse("FAILED",
						"This return predates credit-note values and cannot be printed — its amount is not recoverable.");

			// The original line: the unit rate the goods were sold at, and the customer who bought them.
			Sell sold = r.getSellId() != null ? sellService.findById(r.getSellId()).orElse(null) : null;
			String customerName = null;
			if (sold != null && sold.getCustomerHistory() != null
					&& sold.getCustomerHistory().getCustomer() != null)
				customerName = sold.getCustomerHistory().getCustomer().getName();

			// Same catalog resolve the sell grid does — one batched call, not a lookup per field.
			com.myplus.commerce.contracts.dto.ProductRef p = r.getProductId() != null
					? productRefs(java.util.List.of(r.getProductId())).get(r.getProductId()) : null;

			return new GenericResponse("SUCCESS", "Credit note loaded",
					toCreditNoteDto(r, p, customerName, sold != null ? sold.getSellRate() : null));
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > creditNote " + e.getMessage(), e);
			return new GenericResponse("FAILED", "Could not load the credit note.");
		}
	}

	/**
	 * INST-1 — turn the sale's plan block into a stored plan.
	 *
	 * <h3>Why a refused plan does not fail the sale</h3>
	 * The money has already moved. By the time this runs the invoice is committed, stock is decremented and
	 * the customer has their handset — throwing here would roll back nothing that matters and would report a
	 * completed sale as an error, which is the worst of both. So a refusal is <b>reported in the response
	 * message</b> and the plan is not created: the shopkeeper sees "Sale recorded… Installment plan NOT
	 * created: …" and can fix it from the Installments screen.
	 *
	 * <p>The same reasoning {@code PosOrderRecorder} already applies after {@code addSell}: work that follows
	 * a committed sale is best-effort and never throws.
	 *
	 * @return a message to append when something needs saying, or null when the plan was created cleanly
	 */
	private String createInstallmentPlan(final CustomerHistoryDTO dto, final String invoiceNo) {
		try {
			if (!settingsService.getBool("pos.installment.enabled")) {
				// A default is not a decision: a shop that never turned this on must not silently acquire
				// plans because a client sent the block.
				return "Installment plan NOT created: selling on installment is switched off for this shop.";
			}

			com.myplus.business_service.dto.InstallmentPlanDTO p = dto.getInstallmentPlan();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			Long orgId = user == null ? null : user.getOrganizationId();

			// Read the customer from the COMMITTED INVOICE, not from the request.
			//
			// A walk-in sale carries a name and a contact but no customerId — SagaSaleWriter CREATES the
			// customer during the sale (saveUpdateCustomer) and stamps the resolved row on the invoice. So
			// the request's customerId is null for exactly the case a mobile shop cares about: a new buyer
			// financing their first handset. Reading it from the request refused every such plan while
			// reporting the sale as successful, which is how the first gate run found this.
			//
			// The invoice is the authoritative source anyway: it is what the plan is a structure over.
			com.myplus.business_service.entity.CustomerHistory inv =
					customerHistoryRepo.findByOrganizationIdAndInvoiceNo(orgId, invoiceNo).orElse(null);

			Long customerId = (inv != null && inv.getCustomer() != null)
					? inv.getCustomer().getCustomerId()
					: (dto.getCustomer() != null ? dto.getCustomer().getCustomerId() : null);

			if (customerId == null) {
				// A financed sale against nobody cannot be chased, aged or reminded — the same reasoning
				// D-24 applies to a credit sale, and more sharply here because it runs for months.
				return "Installment plan NOT created: a plan needs a named customer.";
			}

			com.myplus.common.installment.PlanTerms terms = new com.myplus.common.installment.PlanTerms(
					p.getCashPrice(), p.getDownPayment(),
					p.getInstallmentCount() == null ? 0 : p.getInstallmentCount(),
					com.myplus.common.installment.Frequency.fromSetting(p.getFrequency()),
					p.getFirstDueDate(), p.getMarkupAmount());

			String invalid = terms.validate();
			if (invalid != null) return "Installment plan NOT created: " + invalid;

			/*
			 * THE DEPOSIT MUST HAVE BEEN COLLECTED — the invariant, checked where it cannot be bypassed.
			 *
			 * The schedule finances (price − down payment); the invoice records what was actually paid.
			 * Those two are the same money, and until now nothing compared them: a down payment typed
			 * with no cash taken left 5,000 that no instalment covers and no reminder chases, and cash
			 * taken with no down payment entered scheduled 5,000 the customer had already handed over.
			 *
			 * The sale screen now mirrors one field into the other, but a browser is not a control. This
			 * refuses the plan when the money did not arrive, and the SALE STILL STANDS — the existing
			 * contract for a plan that cannot be created — so the shop has a paid invoice to reconcile
			 * rather than a silent mismatch nobody can see.
			 */
			java.math.BigDecimal down = p.getDownPayment() == null
					? java.math.BigDecimal.ZERO : p.getDownPayment();
			java.math.BigDecimal collected = inv == null || inv.getPaidAmount() == null
					? java.math.BigDecimal.ZERO : inv.getPaidAmount();
			if (down.compareTo(collected) > 0) {
				return "Installment plan NOT created: the down payment is " + down.toPlainString()
						+ " but only " + collected.toPlainString() + " was received. Take the deposit first.";
			}

			// Retried on a lost plan-number race, exactly as the invoice is. create() is REQUIRES_NEW, so
			// each attempt gets a fresh transaction — retrying inside the poisoned one is what made this
			// path report "the SALE stands" while the caller's transaction was already rollback-only.
			final Long fOrg = orgId;
			final Long fUser = user == null ? null : user.getUserId();
			final Long fLoc = user == null ? null : user.getActiveLocationId();
			final Long fCustomer = customerId;
			final Long fInvoiceId = inv == null ? null : inv.getCustomer_history_id();
			final com.myplus.common.installment.PlanTerms fTerms = terms;
			final String fAsset = p.getAssetRef();
			com.myplus.business_service.entity.InstallmentPlan plan =
					com.myplus.business_service.util.SequenceRetry.withRetry("installment plan", () ->
							installmentPlanService.create(fTerms, fOrg, fUser, fLoc, fCustomer, fInvoiceId,
									invoiceNo, fAsset));

			return "Installment plan " + plan.getPlanNo() + " created ("
					+ plan.getInstallmentCount() + " payments).";

		} catch (Exception e) {
			LOGGER.error("INST-1: plan creation failed for invoice {} — the SALE stands", invoiceNo, e);
			return "Installment plan NOT created — the sale is recorded. Please add the plan manually.";
		}
	}
}
