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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.Company;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.service.CustomerService;
import com.myplus.business_service.service.ICustomerHistoryService;
import com.myplus.business_service.service.ICustomerService;
// import com.myplus.business_service.service.ICustomerService;
import com.myplus.business_service.service.IItemService;
// import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IPurchaseService;
import com.myplus.business_service.service.ISellService;
import com.myplus.business_service.service.IStockService;
import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.ItemDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.dto.StockDTO;
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
	ICustomerService customerService;

	// @Autowired
	// IItemTypeService itemTypeService;

	@Autowired
	IItemUnitService itemUnitService;

	@Autowired
	IItemService itemService;

	@Autowired
	IPurchaseService purchaseService;
	
	@Autowired
	RequestUtil requestUtil;
	
	@Autowired
	ObjectMapperUtils objectMapperUtils;
	
	@Autowired
	IStockService stockService;

    @Autowired
    private AppUtil appUtil;  

	@Autowired
	ICustomerHistoryService customerHistoryService;

	@Autowired
	com.myplus.business_service.config.TradeSagaProperties tradeSagaProperties;

	@Autowired
	com.myplus.business_service.service.SagaSellService sagaSellService;

	@Autowired
	com.myplus.business_service.repository.ItemCatalogMapRepo itemCatalogMapRepo;

	@Autowired
	com.myplus.commerce.contracts.client.InventoryClient inventoryClient;

	@Autowired
	com.myplus.business_service.service.PaymentService paymentService;

	@Autowired
	com.myplus.business_service.service.TaxService taxService;

	@Autowired
	com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;

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

	/** Role-aware visibility (Phase 7a): a SUPER/owner sees the WHOLE org's data; everyone else sees
	 *  only their own. SUPER_PRIVILEGE travels in the JWT -> gateway X-User-Privileges -> authorities. */
	private boolean seesAllOrg() {
		AuthenticatedUser u = requestUtil.getCurrentUser();
		return u != null && u.getAuthorities() != null && u.getAuthorities().stream()
				.anyMatch(a -> "SUPER_PRIVILEGE".equals(a.getAuthority()));
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
			List<Sell> objs = (page != null && size != null)
					? sellService.findScoped(orgId(), userId(), org.springframework.data.domain.PageRequest.of(page, size))
					: (seesAllOrg() ? sellService.findScoped(orgId(), userId())          // SUPER: whole org
					                : sellService.findOwnScoped(orgId(), userId()));      // others: own only
			if((page == null || size == null) && !(appUtil.isEmptyOrNull(offset) || offset.equals("-1"))) {
				int limit = Integer.valueOf(offset);
				if(objs.size() > limit) objs = new ArrayList<>(objs.subList(0, limit));
			}

			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
			// Batch-fetch the referenced items in ONE query (was an N+1: itemService.findById per Sell row).
			java.util.List<Long> itemIds = objs.stream()
					.filter(s -> s.getProductId() == null && s.getStock() != null && s.getStock().getItemId() != null)
					.map(s -> s.getStock().getItemId())
					.distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, Item> itemsById = itemService.findAllById(itemIds).stream()
					.collect(java.util.stream.Collectors.toMap(Item::getId, java.util.function.Function.identity()));
			// Saga sells carry productId (no local Stock/itemId); resolve their item name via the reverse
			// catalog map (productId -> itemId -> Item) so they list with the same name as legacy sells.
			java.util.List<Long> sagaProductIds = objs.stream()
					.filter(s -> s.getProductId() != null)
					.map(s -> s.getProductId()).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, Item> itemByProductId = new java.util.HashMap<>();
			if (!sagaProductIds.isEmpty()) {
				java.util.Map<Long, Long> productToItem = new java.util.HashMap<>();
				for (Object[] row : itemCatalogMapRepo.findItemIdsByProductIds(sagaProductIds, orgId())) {
					productToItem.put((Long) row[0], (Long) row[1]);
				}
				java.util.Map<Long, Item> sagaItems = itemService.findAllById(productToItem.values()).stream()
						.collect(java.util.stream.Collectors.toMap(Item::getId, java.util.function.Function.identity()));
				sagaProductIds.forEach(pid -> {
					Long iid = productToItem.get(pid);
					if (iid != null && sagaItems.get(iid) != null) itemByProductId.put(pid, sagaItems.get(iid));
				});
			}
			List<SellDTO> dtos=new ArrayList<SellDTO>();
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				// SellDTO dto = appUtil.objTodtoConverter(o);
				SellDTO dto = modelMapper.map(o, SellDTO.class);
				if((appUtil.notEmptyNorNull(o.getStock()) && appUtil.notEmptyNorNull(o.getStock().getItemId())) || o.getProductId() != null) {
					// legacy: item via local Stock's itemId; saga: item via productId reverse-map. Either way the
					// invoice/customer below + dtos.add now run for ALL sells (saga sells were being dropped here).
					Item item = (o.getProductId() != null)
							? itemByProductId.get(o.getProductId())
							: itemsById.get(o.getStock().getItemId());
					if(item != null) {
						dto.setItemId(item.getId());
						dto.setItemName(item.getIname());
						dto.setItemCode(item.getIcode());
						dto.setDescription(item.getIdesc());
					}
					if(o.getStock() != null) {
						modelMapper.addConverter(appUtil.localDateToString);
						modelMapper.addConverter(appUtil.localDateTimeToString);
						StockDTO stock = modelMapper.map(o.getStock(), StockDTO.class);
						dto.setStock(stock);
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
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
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

			// M3c.2b (slice 78): resolve item names productId-FIRST (reverse catalog map) so edit-load no longer needs
			// the Stock FK — and saga sells (no Stock) now load with their name too. Legacy Stock.itemId is a fallback.
			java.util.List<Long> itemIds = lines.stream()
					.filter(s -> s.getProductId() == null && s.getStock() != null && s.getStock().getItemId() != null)
					.map(s -> s.getStock().getItemId()).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, Item> itemsById = itemService.findAllById(itemIds).stream()
					.collect(java.util.stream.Collectors.toMap(Item::getId, java.util.function.Function.identity()));
			java.util.List<Long> invProductIds = lines.stream()
					.filter(s -> s.getProductId() != null).map(Sell::getProductId).distinct()
					.collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, Item> itemByProductId = new java.util.HashMap<>();
			if (!invProductIds.isEmpty()) {
				java.util.Map<Long, Long> productToItem = new java.util.HashMap<>();
				for (Object[] row : itemCatalogMapRepo.findItemIdsByProductIds(invProductIds, orgId()))
					productToItem.put((Long) row[0], (Long) row[1]);
				java.util.Map<Long, Item> invItems = itemService.findAllById(productToItem.values()).stream()
						.collect(java.util.stream.Collectors.toMap(Item::getId, java.util.function.Function.identity()));
				invProductIds.forEach(pid -> {
					Long iid = productToItem.get(pid);
					if (iid != null && invItems.get(iid) != null) itemByProductId.put(pid, invItems.get(iid));
				});
			}

			List<SellDTO> sales = new java.util.ArrayList<>();
			for (Sell s : lines) {
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				SellDTO sd = modelMapper.map(s, SellDTO.class);
				Item item = (s.getProductId() != null) ? itemByProductId.get(s.getProductId())
						: (s.getStock() != null ? itemsById.get(s.getStock().getItemId()) : null);
				if (item != null) { sd.setItemId(item.getId()); sd.setItemName(item.getIname()); sd.setItemCode(item.getIcode()); }
				if (s.getStock() != null) sd.setStock(modelMapper.map(s.getStock(), StockDTO.class));
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
			if (ch == null || !inMyTenant(ch.getOrganizationId(), ch.getUserId()))
				return new GenericResponse("NOT_FOUND", "Invoice not found");           // anti-IDOR
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

			var ts = taxService.settingsFor(orgId());                                  // tax label/reg-no for the header
			out.setTaxLabel(ts.getTaxLabel());
			out.setTaxRegNo(ts.getTaxRegNo());

			// line item names: legacy via local Stock's itemId, saga via productId reverse-map (same as getUserSell)
			java.util.List<Long> itemIds = lines.stream()
					.filter(s -> s.getStock() != null && s.getStock().getItemId() != null)
					.map(s -> s.getStock().getItemId()).distinct().collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, Item> itemsById = itemService.findAllById(itemIds).stream()
					.collect(java.util.stream.Collectors.toMap(Item::getId, java.util.function.Function.identity()));
			java.util.List<Long> sagaProductIds = lines.stream()
					.filter(s -> s.getProductId() != null)
					.map(Sell::getProductId).distinct().collect(java.util.stream.Collectors.toList());
			java.util.Map<Long, Item> itemByProductId = new java.util.HashMap<>();
			if (!sagaProductIds.isEmpty()) {
				java.util.Map<Long, Long> productToItem = new java.util.HashMap<>();
				for (Object[] row : itemCatalogMapRepo.findItemIdsByProductIds(sagaProductIds, orgId()))
					productToItem.put((Long) row[0], (Long) row[1]);
				java.util.Map<Long, Item> sagaItems = itemService.findAllById(productToItem.values()).stream()
						.collect(java.util.stream.Collectors.toMap(Item::getId, java.util.function.Function.identity()));
				sagaProductIds.forEach(pid -> {
					Long iid = productToItem.get(pid);
					if (iid != null && sagaItems.get(iid) != null) itemByProductId.put(pid, sagaItems.get(iid));
				});
			}
			List<SellDTO> sales = new java.util.ArrayList<>();
			for (Sell s : lines) {
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				SellDTO sd = modelMapper.map(s, SellDTO.class);
				Item item = (s.getProductId() != null)
						? itemByProductId.get(s.getProductId())
						: itemsById.get(s.getStock().getItemId());
				if (item != null) { sd.setItemId(item.getId()); sd.setItemName(item.getIname()); sd.setItemCode(item.getIcode()); }
				if (s.getStock() != null) sd.setStock(modelMapper.map(s.getStock(), StockDTO.class));
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

			List<SellDTO> dtos=new ArrayList<SellDTO>(); 
			objs.forEach(obj ->{
				Optional<Item> option = itemService.findById(obj.getStock().getItemId());
				SellDTO dtotemp = modelMapper.map(obj, SellDTO.class);
				if(option.isPresent()) {
					Item item = option.get();
					obj.getStock().setItemId(item.getId());
					dtotemp.setItemId(item.getId());
					dtotemp.setItemName(item.getIname());
					dtotemp.setItemCode(item.getIcode());
					dtotemp.setDescription(item.getIdesc());
					dtotemp.setItemStock(item.getStock() == null? 0: item.getStock().getStock());
					// dtotemp.setSrp(item.getStock().getSrp());
				}
				dtotemp.setDated(appUtil.getDateStr(obj.getDated()));
				dtotemp.setUpdated(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dtotemp);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSell(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now org-scoped + role-aware (SUPER = org, others = own).
			List<Sell> objs = seesAllOrg() ? sellService.findScoped(orgId(), userId())
			                               : sellService.findOwnScoped(orgId(), userId());
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

			// Strangler (slice 33, D2): when enabled, route the sale through the inventory reservation saga
			// (catalog price + reserve/confirm) instead of decrementing local Stock. SagaSellService manages
			// its own committed transactions (REQUIRES_NEW), so it is safe to call inside this @Transactional.
			if (tradeSagaProperties.isEnabled()) {
				String invoiceNo = sagaSellService.addSell(dto);
				return new GenericResponse("SUCCESS",
						invoiceNo != null ? "Sale recorded successfully. Invoice " + invoiceNo : "Sale recorded successfully.",
						invoiceNo);
			}

			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			Customer customerObj = customerService.saveUpdateCustomer(dto);
			customerService.save(customerObj);

			CustomerHistory customerHistory =   customerHistoryService.saveUpdateCustomerHistory(dto);

			customerHistory.setCustomer(customerObj);
			customerHistoryService.save(customerHistory);

			// Running balance is the sum of this customer's invoice headers — recompute now that this
			// sale's header exists, so under/over-payments carry across invoices correctly.
			customerService.recomputeDue(customerObj);

			List<SellDTO> sells = ObjectMapperUtils.mapAll(dto.getSales(), SellDTO.class);
			for (SellDTO sell : sells) {
				sell.setCustomerHistory(modelMapper.map(customerHistory, CustomerHistoryDTO.class));
				// sell.setCustomerHistory(dto);
			}

			sellService.addSell(ObjectMapperUtils.mapAll(sells, Sell.class));

			// slice 22: surface the generated per-org invoice number to the cashier/receipt
			String invoiceNo = customerHistory.getInvoiceNo();
			return new GenericResponse("SUCCESS",
					invoiceNo != null ? "Sale recorded successfully. Invoice " + invoiceNo : "Sale recorded successfully.",
					invoiceNo);

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
	@ExceptionHandler(Exception.class)
	public GenericResponse handleUncaught(Exception e) {
		return new GenericResponse("ERROR", e.getMessage());
	}
		
/*	@RequestMapping(value = "/addSell", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addSell(@Validated final SellDTO dto, final HttpServletRequest request) {
		try {
			Sell obj= new Sell();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			obj = modelMapper.map(dto, Sell.class);
			obj.setUserId(user.getUserId());
			//if update
			Item item = itemService.getOne(dto.getItemId());
        	Float stock = item.getStock()-dto.getQuantity();
			if(!appUtil.isEmptyOrNull(dto.getId())){
				Sell objTemp = sellService.getOne(dto.getId());
				if(objTemp.getQuantity() > dto.getQuantity())
					stock = item.getStock() - (dto.getQuantity() - objTemp.getQuantity());
				else
					stock = item.getStock() + (objTemp.getQuantity() - dto.getQuantity());
				
				item.setStock(stock);	
			}
			//updating stock
			item.setStock(stock);
	        itemService.save(item);
//			//updating stock
	        obj.setStock(stock);
	        obj.setDated(dated);
			obj.setUpdated(dated);

			obj = sellService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getMessage());
		}
	}
*/	
	
	
	/**
	 * Update an existing sale (invoice) in place — the "edit" counterpart of addSell. Keeps the SAME
	 * invoice number; adjusts stock by the NET per-item delta (old sold qty given back − new sold qty);
	 * recomputes the customer due. All-or-nothing (@Transactional). The frontend routes here (instead of
	 * addSell) when it carries a customer_history_id (an edit in progress).
	 */
	/** M3c.3b (slice 80): resolve a sell line's catalog productId — its own productId, else mapped from itemId. */
	private Long productIdOfLine(SellDTO s) {
		if (s.getProductId() != null && s.getProductId() > 0) return s.getProductId();
		if (s.getItemId() != null && s.getItemId() > 0)
			return itemCatalogMapRepo.findProductIdByItemId(s.getItemId(), orgId()).orElse(null);
		return null;
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
			if (!inMyTenant(ch.getOrganizationId(), ch.getUserId()))
				return new GenericResponse("NOT_FOUND", "Invoice not found");

			// 1) Net stock change per stock_id = (old sold qty given back) − (new sold qty taken).
			List<Sell> oldLines = sellService.findByInvoiceScoped(chId, orgId(), userId());
			// M3c.3b (slice 80): net per-PRODUCT delta = old sold qty − new sold qty. Adjust INVENTORY (not local
			// Stock): a positive delta returns the excess (importStock); a negative delta takes more via one
			// reservation+confirm (reject the edit if out of stock). Edits become inventory-correct and Stock-free.
			java.util.Map<Long, Float> delta = new java.util.HashMap<>();
			for (Sell o : oldLines) {
				Long pid = (o.getProductId() != null) ? o.getProductId()
						: (o.getStock() != null && o.getStock().getItemId() != null
							? itemCatalogMapRepo.findProductIdByItemId(o.getStock().getItemId(), orgId()).orElse(null) : null);
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

			// 3) Delete the original line items (replaced below).
			for (Sell o : oldLines) sellService.deleteById(o.getSellId());

			// 4) Update customer + invoice header IN PLACE — KEEP invoiceSeq/invoiceNo (no new number).
			Customer customerObj = customerService.saveUpdateCustomer(dto);
			customerService.save(customerObj);
			ch.setCustomer(customerObj);
			ch.setUserId(user.getUserId());
			ch.setOrganizationId(user.getOrganizationId());
			ch.setUpdated(java.time.LocalDateTime.now());
			java.math.BigDecimal paid = dto.getPaidAmount() != null ? dto.getPaidAmount()
					: (dto.getCustomer() != null ? dto.getCustomer().getPaidAmount() : null);
			java.math.BigDecimal due = dto.getDueAmount() != null ? dto.getDueAmount()
					: (dto.getCustomer() != null ? dto.getCustomer().getDueAmount() : null);
			if (paid != null) ch.setPaidAmount(paid);
			if (due != null) ch.setDueAmount(due);
			if (dto.getDueDate() != null) ch.setDueDate(dto.getDueDate());
			customerHistoryService.save(ch);

			// Recompute the customer's running balance from all their invoice headers — this edited
			// header now carries its new (paid − bill), so the prior balance + this change are correct
			// without any lossy in-place reversal of the original amounts.
			customerService.recomputeDue(customerObj);

			// 5) Insert the edited line items productId-first (no local Stock; inventory already adjusted above).
			for (SellDTO s : dto.getSales()) {
				Sell line = new Sell();
				line.setUserId(user.getUserId());
				line.setOrganizationId(user.getOrganizationId());
				line.setQuantity(s.getQuantity());
				line.setTotalAmount(s.getTotalAmount());
				line.setNetAmount(s.getNetAmount());
				line.setSrp(s.getSrp());
				line.setTaxRate(s.getTaxRate());
				line.setTaxAmount(s.getTaxAmount());
				line.setProductId(productIdOfLine(s));               // preserve catalog product identity
				if (s.getTotalAmount() != null && s.getQuantity() != null && s.getQuantity() > 0f)
					line.setSellRate(s.getTotalAmount().divide(java.math.BigDecimal.valueOf(s.getQuantity()),
							2, java.math.RoundingMode.HALF_UP));   // unit rate from the line total
				line.setDated(java.time.LocalDateTime.now());
				line.setUpdated(java.time.LocalDateTime.now());
				line.setCustomerHistory(ch);
				sellService.save(line);
			}

			return new GenericResponse("SUCCESS", "Sale updated. Invoice " + ch.getInvoiceNo(), ch.getInvoiceNo());
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > updateSell " + e.getMessage(), e);
			// Propagate past @Transactional so the whole edit rolls back (all-or-nothing).
			throw new RuntimeException("Could not update the sale. Please try again.", e);
		}
	}

	@PostMapping(value = "/addSelling")
	@ResponseBody
	public GenericResponse addSelling(@RequestBody final List<SellDTO> dtos, final HttpServletRequest request) {
		try {
//			AgricultureIncome obj= new AgricultureIncome();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			List<Sell> objs = ObjectMapperUtils.mapAll(dtos, Sell.class);
			objs.forEach(obj ->{
				obj.setUserId(user.getUserId());                       // audit
				obj.setOrganizationId(user.getOrganizationId());       // tenant scope
				if(obj.getStock() == null) return;
				//if update
				Item item = itemService.getReferenceById(obj.getStock().getItemId());
	        	Float stock = item.getStock().getStock() - obj.getQuantity();
				if(!appUtil.isEmptyOrNull(obj.getSellId())){
					Sell objTemp = sellService.getReferenceById(obj.getSellId());
					if(objTemp.getQuantity() > obj.getQuantity())
						stock = item.getStock().getStock() - (obj.getQuantity() - objTemp.getQuantity());
					else
						stock = item.getStock().getStock() + (objTemp.getQuantity() - obj.getQuantity());
					
					item.getStock().setStock(stock);
				}
				//updating stock
				item.getStock().setStock(stock);
		        itemService.save(item);
	//			//updating stock
//		        obj.setStock(stock);
		        obj.setDated(dated);
				obj.setUpdated(dated);
	
				obj = sellService.save(obj);
			});
			return new GenericResponse("SUCCESS", "Sale recorded successfully.");
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}

	@RequestMapping(value = "/revertSell", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse reverSell(@Validated final SellDTO dto, final HttpServletRequest request) {
		try {
			Sell obj= new Sell();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			obj = modelMapper.map(dto, Sell.class);
			obj.setUserId(user.getUserId());                       // audit
			obj.setOrganizationId(user.getOrganizationId());       // tenant scope
			if(appUtil.isEmptyOrNull(dto.getSellId()))
				return new GenericResponse("NOT_FOUND");
				
			Optional<Item> o = itemService.findById(dto.getStock().getItemId());
			if(!o.isPresent())
				return new GenericResponse("NOT_FOUND");
			
			Item item = o.get();
        	Float stock = Float.sum(item.getStock().getStock(),dto.getQuantity());
			//updating stock
			item.getStock().setStock(stock);
	        itemService.save(item);
	        
	        Sell s = sellService.getReferenceById(dto.getSellId());
	        
//			obj.setDiscount(s.getDiscount() - dto.getStock().getse);
			obj.setNetAmount(s.getNetAmount().subtract(dto.getNetAmount()));
			obj.setTotalAmount(s.getTotalAmount().subtract(dto.getTotalAmount()));
			obj.setQuantity(s.getQuantity() - dto.getQuantity());
			
			//rollback stock
//	        obj.setStock(stock);
	        obj.setDated(dated);
			obj.setUpdated(dated);

			obj = sellService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to revert sale. Please try again.");
			}else {
				return new GenericResponse("SUCCESS", "Sale reverted successfully.");
			}
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}

	@RequestMapping(value = "/deleteSell", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteSell( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					Long sid = Long.valueOf(id);
					Sell existing = sellService.findById(sid).orElse(null);
					if(existing == null) continue;
					if(inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						sellService.deleteById(sid);
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			appUtil.le(this.getClass(),e);
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
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
			if(existingSell == null || !inMyTenant(existingSell.getOrganizationId(), existingSell.getUserId()))
				return new GenericResponse("NOT_FOUND");

			// G2 (slice 34) input validation: return qty must be > 0 and not exceed what was sold on this line —
			// otherwise the fallback restock would inflate StockLevel beyond what left. (Bean-Validation standard, slice 26.)
			float soldQty = existingSell.getQuantity() != null ? existingSell.getQuantity() : 0f;
			float retQty = dto.getQuantity() != null ? dto.getQuantity() : 0f;
			if(retQty <= 0f)
				return new GenericResponse("FAILED", "Return quantity must be greater than 0.");
			if(retQty > soldQty)
				return new GenericResponse("FAILED", "Cannot return more than the sold quantity (" + soldQty + ").");

			// G2 (slice 34): a saga sell decremented inventory-service (StockEntry/StockLevel), not local Stock.
			// Route its return back through inventory (inverse saga) so on-hand is restored, not just local Stock.
			CustomerHistory ch = existingSell.getCustomerHistory();
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
				// M3c.3 (slice 79): a backfilled legacy sell has a productId but no reservation — restock INVENTORY by
				// product (a fresh entry) so inventory stays authoritative even for legacy returns; no local Stock write.
				inventoryClient.importStock(java.util.List.of(
						com.myplus.commerce.contracts.dto.StockImportLine.builder()
								.productId(existingSell.getProductId()).quantity(dto.getQuantity()).build()));
			} else if (!appUtil.isEmptyOrNull(dto.getSellSId())) {
				// Pre-backfill legacy row (no productId) — last-resort local Stock restock.
				Optional<Stock> stockOpt = stockService.findById(dto.getSellSId());
				if(stockOpt.isPresent()) {
					Stock stock = stockOpt.get();
					stock.setStock(stock.getStock() + dto.getQuantity());
					stockService.save(stock);
				}
			}

			// G5 (slice 37): record the money refunded to the customer (proportional to the returned qty), so the
			// payment ledger matches the restored stock. A REFUND tender (negative) is written to the invoice.
			boolean partial = retQty > 0f && retQty < soldQty;
			if (ch != null && ch.getCustomer_history_id() != null) {
				java.math.BigDecimal lineGross = nzbd(existingSell.getTotalAmount()).add(nzbd(existingSell.getTaxAmount()));
				java.math.BigDecimal refund = partial
						? lineGross.multiply(java.math.BigDecimal.valueOf(retQty))
								.divide(java.math.BigDecimal.valueOf(soldQty), 2, java.math.RoundingMode.HALF_UP)
						: lineGross;   // full-line return (or unknown qty) → refund the whole line
				if (refund.signum() > 0)
					paymentService.refund(ch.getCustomer_history_id(), refund, orgId(), userId());
			}

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
				ch.setDueAmount(nzbd(ch.getPaidAmount()).subtract(grandTotal));
				ch.setUpdated(java.time.LocalDateTime.now());
				customerHistoryService.save(ch);

				Customer customer = ch.getCustomer();
				if (customer != null)
					customerService.recomputeDue(customer);
			}

			return new GenericResponse("SUCCESS", "Sale returned successfully.");

		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > saleReturn " + e.getCause(), e);
			return new GenericResponse("FAILED", "An unexpected error occurred. Please contact support.");
		}
	}
}
