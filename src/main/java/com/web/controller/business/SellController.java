package com.web.controller.business;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.Transactional;

import org.apache.http.HttpStatus;
import org.apache.http.protocol.HTTP;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.persistence.model.Company;
import com.persistence.model.User;
import com.persistence.model.business.Customer;
import com.persistence.model.business.CustomerHistory;
import com.persistence.model.business.Item;
import com.persistence.model.business.Sell;
import com.persistence.model.business.Stock;
import com.service.business.CustomerService;
import com.service.business.ICustomerHistoryService;
import com.service.business.ICustomerService;
// import com.service.business.ICustomerService;
import com.service.business.IItemService;
// import com.service.business.IItemTypeService;
import com.service.business.IItemUnitService;
import com.service.business.IPurchaseService;
import com.service.business.ISellService;
import com.service.business.IStockService;
import com.web.dto.business.CustomerDTO;
import com.web.dto.business.CustomerHistoryDTO;
import com.web.dto.business.ItemDTO;
import com.web.dto.business.SellDTO;
import com.web.dto.business.StockDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.ObjectMapperUtils;
import com.web.util.RequestUtil;

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
			Sell filterBy = new Sell();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Sell> example = Example.of(filterBy);
	        
	        List<Sell> objs;
	        if(appUtil.isEmptyOrNull(offset) || offset.equals("-1"))
				objs = sellService.findAll(example);
	        else
	        	objs = sellService.findAll(example,appUtil.getPageRequest(0,Integer.valueOf(offset),appUtil.orderByDESC("sellId"))).getContent();

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
						Item item  = option.get();
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
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/loadSR", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse loadSR(final SellDTO dto, final HttpServletRequest request) {
		int CURRENT_MONTH = 0;
		try {
			User user = requestUtil.getCurrentUser();
	        List<Sell> objs=null;
	        if(dto.getRp() == CURRENT_MONTH) {
	        	objs = sellService.findSellByDates(appUtil.firstDateTimeOfMonth(),appUtil.lastDateTimeOfMonth(), user.getId());
	        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByDates(appUtil.getDateTime(dto.getSd()), appUtil.getDateTime(dto.getEd()), user.getId());
	        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByStartDate(appUtil.getDateTime(dto.getSd()), user.getId());
	        }else if(appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
	        	objs = sellService.findSellByEndDate(appUtil.getDateTime(dto.getEd()), user.getId());
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
					Item item  = option.get();
					obj.getStock().setItemId(item.getId());
					dtotemp.setItemId(item.getId());
					dtotemp.setItemName(item.getIname());
					dtotemp.setItemCode(item.getIcode());
					dtotemp.setDescription(item.getIdesc());
					dtotemp.setItemStock(item.getStock().getStock());
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
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSell(final HttpServletRequest request) {
		try {
			List<Sell> objs = sellService.findAll();
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
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addSell", method = RequestMethod.POST)
	@ResponseBody
	@Transactional
	public GenericResponse addSell(@RequestBody final CustomerHistoryDTO dto, final HttpServletRequest request) {
		try {
			if (dto == null || appUtil.isEmptyOrNull(dto.getSales()))
				return new GenericResponse("ERROR", "No sales data provided");

			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			dto.setUserType(user.getUserType());

			Customer customerObj = customerService.saveUpdateCustomer(dto);
			dto.setCustomer(modelMapper.map(customerObj, CustomerDTO.class));

			CustomerHistory customerHistory =   customerHistoryService.saveUpdateCustomerHistory(dto);

			if (appUtil.isEmptyOrNull(customerObj.getName()) || appUtil.isEmptyOrNull(customerObj.getContact()) ) {
				dto.setCustomer(null);
			} else {
				customerService.save(customerObj);
				customerHistory.setCustomer(customerObj);
			}
			customerHistoryService.save(customerHistory);

			List<SellDTO> sells = ObjectMapperUtils.mapAll(dto.getSales(), SellDTO.class);
			for (SellDTO sell : sells) {
				sell.setCustomerHistory(modelMapper.map(customerHistory, CustomerHistoryDTO.class));
				// sell.setCustomerHistory(dto);
			}

			sellService.addSell(ObjectMapperUtils.mapAll(sells, Sell.class));

			return new GenericResponse(appUtil.SUCCESS,messages.getMessage(appUtil.SUCCESS, null,"msg.sale.saved", request.getLocale()));
			
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addSell "+e.getCause());
			String cause = e.getCause() != null ? e.getCause().toString() : e.getMessage();
			return new GenericResponse("ERROR",messages.getMessage(appUtil.ERROR,null,"message.error_system_error"+e.getMessage(), request.getLocale()),
					cause);
		}
	}
		
/*	@RequestMapping(value = "/addSell", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addSell(@Validated final SellDTO dto, final HttpServletRequest request) {
		try {
			Sell obj= new Sell();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			obj = modelMapper.map(dto, Sell.class);
			obj.setUserId(user.getId());
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
					e.getCause().toString());
		}
	}
*/	
	
	
	@PostMapping(value = "/addSelling")
	@ResponseBody
	public GenericResponse addSelling(@RequestBody final List<SellDTO> dtos, final HttpServletRequest request) {
		try {
//			AgricultureIncome obj= new AgricultureIncome();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			List<Sell> objs = ObjectMapperUtils.mapAll(dtos, Sell.class);
			objs.forEach(obj ->{
				obj.setUserId(user.getId());
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
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/revertSell", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse reverSell(@Validated final SellDTO dto, final HttpServletRequest request) {
		try {
			Sell obj= new Sell();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			obj = modelMapper.map(dto, Sell.class);
			obj.setUserId(user.getId());
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
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
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
					sellService.deleteById(Long.valueOf(id));
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
	@Transactional
	public GenericResponse saleReturn(final SellDTO dto, final HttpServletRequest request) {	
//	public GenericResponse saleReturn(@RequestParam final Long saleId,@RequestParam final Long stockId,@RequestParam final Float qty) {
		try {
			if(appUtil.isEmptyOrNull(dto.getSellId()) || appUtil.isEmptyOrNull(dto.getSellSId())) 
				return new GenericResponse("NOT_FOUND");;
			
			Optional<Stock> stockOpt = stockService.findById(dto.getSellSId());
			if(stockOpt.isPresent()) {
				Stock stock = stockOpt.get();
				stock.setStock(stock.getStock() + dto.getQuantity());
				
				stockService.save(stock);
				if(appUtil.isEmptyOrNull(stock)) {
					return new GenericResponse("FAILED",messages.getMessage(appUtil.FAILED,null,"Sale returned Fail",requestUtil.getCurrentHttpRequest().getLocale()));
				}
			}			
			sellService.deleteById(dto.getSellId());
			return new GenericResponse("SUCCESS",messages.getMessage(appUtil.SUCCESS,null,"Sale return successfully", requestUtil.getCurrentHttpRequest().getLocale()));
			
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause());
			return new GenericResponse("FAILED",messages.getMessage(appUtil.ERROR,null,"Sale return Fail", requestUtil.getCurrentHttpRequest().getLocale()));
		}
	}
}
