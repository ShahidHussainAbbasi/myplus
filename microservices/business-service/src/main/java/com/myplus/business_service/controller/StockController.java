package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.IItemService;
// import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IStockService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.ItemDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@RestController
public class StockController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IStockService service;

	@Autowired
	IItemService itemService;

	@Autowired
	ICompanyService companyService;

	// @Autowired
	// IItemTypeService itemTypeService;

	@Autowired
	IItemUnitService itemUnitService;

	@Autowired
	IVenderService venderService;

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

	@RequestMapping(value = "/getUserStock", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItem(final HttpServletRequest request) {
		try {
			List<Item> objs = itemService.findScoped(orgId(), userId());
			if (appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",
						messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<ItemDTO> dtos = new ArrayList<ItemDTO>();
			objs.forEach(obj -> {
				modelMapper.addConverter(appUtil.localDateToString);
				modelMapper.addConverter(appUtil.localDateTimeToString);
				ItemDTO dto = modelMapper.map(obj, ItemDTO.class);
				
//				dto.setVenderId(obj.getVender().getId());
//				dto.setVenderName(obj.getVender().getName());
//				dto.setVenderIds(obj.getVenders().stream().map(Vender::getId).collect(Collectors.toSet()));
//				dto.setVenderNames(obj.getVenders().stream().map(Vender::getName).collect(Collectors.toSet()));
//				dto.setItemUnitIds(obj.getItemUnits().stream().map(ItemUnit::getId).collect(Collectors.toSet()));
//				dto.setItemUnitNames(obj.getItemUnits().stream().map(ItemUnit::getName).collect(Collectors.toSet()));
//				dto.setItemTypeIds(obj.getItemTypes().stream().map(ItemType::getId).collect(Collectors.toSet()));
//				dto.setItemTypeNames(obj.getItemTypes().stream().map(ItemType::getName).collect(Collectors.toSet()));

//				Company company = companyService.getOne(dto.getCompanyId());
//				dto.setCompanyId(company.getId());
//				dto.setCompanyName(company.getName());
				if(!appUtil.isEmptyOrNull(obj.getCompany())) {
					dto.setCompanyId(obj.getCompany().getId());
					dto.setCompanyName(obj.getCompany().getName());
				}
				if(!appUtil.isEmptyOrNull(dto.getVenderId())) {
					Vender vender = venderService.getOne(dto.getVenderId());
					dto.setVenderId(vender.getId());
					dto.setVenderName(vender.getName());
				}
//				if(!AppUtil.isEmptyOrNull(obj.getItemType())) {
//					dto.setItemTypeId(obj.getItemType().getId());
//					dto.setItemTypeName(obj.getItemType().getName());
//				}
//				if(!AppUtil.isEmptyOrNull(obj.getItemUnit())) {
//					dto.setItemUnitId(obj.getItemUnit().getId());
//					dto.setItemUnitName(obj.getItemUnit().getName());
//				}
//				Collection<ItemType> itemTypes = itemTypeService.findAllById(dto.getItemTypeIds()); 
//				dto.setItemTypeIds(itemTypes.stream().map(ItemType::getId).collect(Collectors.toSet()));
//				dto.setItemTypeNames(itemTypes.stream().map(ItemType::getName).collect(Collectors.toSet()));
//				Collection<ItemUnit> itemUnits = itemUnitService.findAllById(dto.getItemUnitIds()); 
//				dto.setItemUnitIds(itemUnits.stream().map(ItemUnit::getId).collect(Collectors.toSet()));
//				dto.setItemUnitNames(itemUnits.stream().map(ItemUnit::getName).collect(Collectors.toSet()));
				
//				dto.setExpDateStr(appUtil.getLocalDateStr(obj.getExpDate()));
//				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
//				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",
					messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getUserItem " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}

	@RequestMapping(value = "/getUserStocks", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItems(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<Item> objs = itemService.findScoped(orgId(), userId());
			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" +d.getIcode()+" ~ "+d.getIname() + "</option>");
			});
			return sb.toString();
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause(), e);
			return (sb.append("<option value=''> Item not available </option>")).toString();
		}
	}

	@RequestMapping(value = "/getStock", method = RequestMethod.GET)
	@ResponseBody
	public StockDTO getStock(@RequestParam final Long itemId) {
		try {
			if(appUtil.isEmptyOrNull(itemId))
				return null;

			// anti-IDOR: only expose stock for an item that belongs to the caller's tenant
			Item ownerItem = itemService.findById(itemId).orElse(null);
			if(ownerItem == null || !inMyTenant(ownerItem.getOrganizationId(), ownerItem.getUserId()))
				return null;

			Stock obj = new Stock();
//			obj.setBatchNo(batchNo);
			obj.setItemId(itemId);
			Example<Stock> example = Example.of(obj) ;
			List<Stock> stocks = service.findAll(example);
			StockDTO dto = new StockDTO();
			Float stock=0.0F;
			if(!appUtil.isEmptyOrNull(stocks)) {
//				return stock.get();
				for(Stock s:stocks){
					stock +=s.getStock();
				}
				//if sell is item base not batch base then populate first item from the list
				Stock s = stocks.get(0);
				dto = modelMapper.map(s, StockDTO.class);
//				dto.setBpurchaseDiscountType(stocks.get(0).getBpurchaseDiscountType());
//				dto.setBpurchaseDiscount(stocks.get(0).getBpurchaseDiscount());
				
				dto.setStock(stock);
			}else {
				dto.setBpurchaseDiscountType("%");
				dto.setBpurchaseDiscount(java.math.BigDecimal.ZERO);
				dto.setStock(0.0F);
			}
			//fetch item description
			Optional<Item> itemOpt = itemService.findById(itemId);
			if(itemOpt.isPresent()) {
				dto.setIDesc(itemOpt.get().getIdesc());
			}else {
				dto.setIDesc("Item not registered");
			}
				
			return dto;
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause(), e);
			return null;
		}
	}

	@RequestMapping(value = "/getStockByBatch", method = RequestMethod.GET)
	@ResponseBody
	public Stock getStockByBatch(@RequestParam final String batchNo) {
			if(appUtil.isEmptyOrNull(batchNo))
				return null;
			
			try {
				// tenant-scoped batch lookup (own org + caller's pre-migration org-NULL rows)
				return service.findByBatchScoped(batchNo, orgId(), userId()).orElse(null);

			} catch (Exception e) {
				LOGGER.error(this.getClass().getName() + " > getStockByBatch " + e.getCause(), e);
			}
			return null;
	}

	@RequestMapping(value = "/getBatchesByItem", method = RequestMethod.GET)
	@ResponseBody
	public String getBatchesByItem(@RequestParam final Long itemId) {
			if(appUtil.isEmptyOrNull(itemId))
				return null;
			
			StringBuffer sb = new StringBuffer();
			try {
				// tenant-scoped batches (own org + caller's pre-migration org-NULL rows)
				Set<String> batches = service.getItemBatchScoped(orgId(), userId(), itemId);
				sb.append("<option value=''> Nothing Selected </option>");
				sb.append("<option value='0'> Default </option>");
				batches.forEach(batch -> {
					sb.append("<option value='" + batch + "'>" + batch + "</option>");
				});
				return sb.toString();
			} catch (Exception e) {
				LOGGER.error(this.getClass().getName() + " > getItemBatch " + e.getCause(), e);
				return (sb.append("<option value=''> Unable to find item batch </option>")).toString();
			}
	}

	@RequestMapping(value = "/getAllStock", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStock(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Item> objs = itemService.findScoped(orgId(), userId());
			if (appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",
						messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<ItemDTO> dtos = new ArrayList<ItemDTO>();
			objs.forEach(obj -> {
				modelMapper.addConverter(appUtil.localDateToString);
				modelMapper.addConverter(appUtil.localDateTimeToString);
				ItemDTO dto = modelMapper.map(obj, ItemDTO.class);
//				dto.setCompanyId(obj.getCompany().getId());
//				dto.setCompanyName(obj.getCompany().getName());
//				dto.setVenderId(obj.getVender().getId());
//				dto.setVenderName(obj.getVender().getName());
//				dto.setItemUnitIds(obj.getItemUnits().stream().map(ItemUnit::getId).collect(Collectors.toSet()));
//				dto.setItemUnitNames(obj.getItemUnits().stream().map(ItemUnit::getName).collect(Collectors.toSet()));
//				dto.setItemTypeIds(obj.getItemTypes().stream().map(ItemType::getId).collect(Collectors.toSet()));
//				dto.setItemTypeNames(obj.getItemTypes().stream().map(ItemType::getName).collect(Collectors.toSet()));
//				dto.setExpDateStr(appUtil.getLocalDateStr(obj.getExpDate()));
//				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
//				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if (appUtil.isEmptyOrNull(objs)) {
				return new GenericResponse("NOT_FOUND",
						messages.getMessage("message.userNotFound", null, request.getLocale()), objs);
			} else {
				return new GenericResponse("SUCCESS",
						messages.getMessage("message.userNotFound", null, request.getLocale()), objs);
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getAllItem " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}

	@RequestMapping(value = "/addStock", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addStock(@Validated final ItemDTO dto, final HttpServletRequest request) {
		try {
			Item obj= new Item();
//			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());
//			if(appUtil.isEmptyOrNull(dto.getStock()))
//				dto.setStock(0F);
//			if(appUtil.isEmptyOrNull(dto.getDiscountType()))
//				dto.setDiscountType("%");
			
			if(appUtil.isEmptyOrNull(dto.getId())){
				// dup-name check within the active tenant (was a userId-only Example probe)
				boolean exists = itemService.findScoped(orgId(), userId()).stream()
						.anyMatch(i -> i.getIname()!=null && i.getIname().equalsIgnoreCase(dto.getIname()));
				if(exists)
					return new GenericResponse("FOUND", "The Item '"+dto.getIname()+"' already exists.");
			}

			modelMapper.addConverter(appUtil.stringToLocalDate);
			modelMapper.addConverter(appUtil.stringToLocalDateTime);
			obj = modelMapper.map(dto, Item.class);
			obj.setUserId(user.getUserId());                  // audit
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope
//			if(!appUtil.isEmptyOrNull(dto.getExpDateStr()))
//				obj.setExpDate(appUtil.getLocalDate(dto.getExpDateStr()));
			
//			obj.setDated(dated);
//			obj.setUpdated(dated);
			// add company
			// add company
//			if (!AppUtil.isEmptyOrNull(dto.getCompanyId()))
//				obj.setCompany(companyService.getOne(dto.getCompanyId()));
//			else
//				obj.setCompany(null);
			// add vender
//			if (!AppUtil.isEmptyOrNull(dto.getVenderId()))
//				obj.setVender(venderService.getOne(dto.getVenderId()));
//			else
//				obj.setVender(null);

//			if (!AppUtil.isEmptyOrNull(dto.getItemTypeIds()))
//				obj.setItemTypes(itemTypeService.findAllById(dto.getItemTypeIds()));
//			else
//				obj.setItemTypes(null);

//			if (!AppUtil.isEmptyOrNull(dto.getItemUnitIds()))
//				obj.setItemUnits(itemUnitService.findAllById(dto.getItemUnitIds()));
//			else
//				obj.setItemUnits(null);

			obj = itemService.save(obj);
			if (appUtil.isEmptyOrNull(obj.getId())) {
				return new GenericResponse("FAILED",
						messages.getMessage("message.userNotFound", null, request.getLocale()));
			} else {
				return new GenericResponse("SUCCESS",
						messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > addItem " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getMessage());
		}
	}

	@RequestMapping(value = "/deleteStock", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteStock(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for (String id : idList) {
					Long iid = Long.valueOf(id);
					Item existing = itemService.findById(iid).orElse(null);
					if (existing == null) continue;
					if (inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						itemService.deleteById(iid);
				}
				return true;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),"SUCCESS");
			} else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
								// request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > deleteItem " + e.getCause(), e);
			return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),
		}
	}
}
