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

	ModelMapper modelMapper = new ModelMapper();
	{
		modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
	}

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	/** True if a row (by its org/user) belongs to the caller's tenant, incl. their pre-migration org-NULL rows. */
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
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
	public GenericResponse getUserSell(final HttpServletRequest request) {
		try {
			String offset = request.getParameter("q");
			// tenant-scoped, newest-first; apply the "recent N" cap in memory when an offset is given
			List<Sell> objs = sellService.findScoped(orgId(), userId());
			if(!(appUtil.isEmptyOrNull(offset) || offset.equals("-1"))) {
				int limit = Integer.valueOf(offset);
				if(objs.size() > limit) objs = new ArrayList<>(objs.subList(0, limit));
			}

			if(appUtil.isEmptyOrNull(objs)){	
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
			List<SellDTO> dtos=new ArrayList<SellDTO>(); 
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				// SellDTO dto = appUtil.objTodtoConverter(o);
				SellDTO dto = modelMapper.map(o, SellDTO.class);
				if(appUtil.notEmptyNorNull(o.getStock()) && appUtil.notEmptyNorNull(o.getStock().getItemId())) {
					Optional<Item> option = itemService.findById(o.getStock().getItemId());
					if(option.isPresent()) {
						Item item = option.get();
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
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
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
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSell(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Sell> objs = sellService.findScoped(orgId(), userId());
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
			e.printStackTrace();
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

			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			Customer customerObj = customerService.saveUpdateCustomer(dto);
			customerService.save(customerObj);

			CustomerHistory customerHistory =   customerHistoryService.saveUpdateCustomerHistory(dto);

			customerHistory.setCustomer(customerObj);
			customerHistoryService.save(customerHistory);

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
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addSell "+e.getCause());
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
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getMessage());
		}
	}
*/	
	
	
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
			// String status = sellService.createReport(objs);
			return new GenericResponse("SUCCESS", "Sale recorded successfully.");
		} catch (Exception e) {
			e.printStackTrace();
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
			obj.setNetAmount(s.getNetAmount() - dto.getNetAmount());
			obj.setTotalAmount(s.getTotalAmount() - dto.getTotalAmount());
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
			e.printStackTrace();
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
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}

	@PostMapping(value = "/saleReturn")
	@ResponseBody
	public GenericResponse saleReturn(final SellDTO dto, final HttpServletRequest request) {
//	public GenericResponse saleReturn(@RequestParam final Long saleId,@RequestParam final Long stockId,@RequestParam final Float qty) {
		try {
			if(appUtil.isEmptyOrNull(dto.getSellId()) || appUtil.isEmptyOrNull(dto.getSellSId()))
				return new GenericResponse("NOT_FOUND");;

			// anti-IDOR: only let the caller return a sale that belongs to their tenant
			Sell existingSell = sellService.findById(dto.getSellId()).orElse(null);
			if(existingSell == null || !inMyTenant(existingSell.getOrganizationId(), existingSell.getUserId()))
				return new GenericResponse("NOT_FOUND");

			Optional<Stock> stockOpt = stockService.findById(dto.getSellSId());
			if(stockOpt.isPresent()) {
				Stock stock = stockOpt.get();
				stock.setStock(stock.getStock() + dto.getQuantity());
				
				stockService.save(stock);
				if(appUtil.isEmptyOrNull(stock)) {
					return new GenericResponse("FAILED", "Sale return failed. Please try again.");
				}
			}
			sellService.deleteById(dto.getSellId());
			return new GenericResponse("SUCCESS", "Sale returned successfully.");

		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > saleReturn " + e.getCause());
			return new GenericResponse("FAILED", "An unexpected error occurred. Please contact support.");
		}
	}
}
