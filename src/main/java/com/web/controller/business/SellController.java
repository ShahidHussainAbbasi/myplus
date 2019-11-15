package com.web.controller.business;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.persistence.model.User;
import com.persistence.model.business.Item;
import com.persistence.model.business.Sell;
import com.service.business.ICustomerService;
import com.service.business.IItemService;
import com.service.business.IItemTypeService;
import com.service.business.IItemUnitService;
import com.service.business.IPurchaseService;
import com.service.business.ISellService;
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

	@Autowired
	IItemTypeService itemTypeService;

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
    private AppUtil appUtil;  
    
	ModelMapper modelMapper = new ModelMapper();
	
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
//			Page<AgricultureIncome> objsss = sellService.findAll(example,AppUtil.getPageRequest(AppUtil.orderByDESC("id")));
	        if(appUtil.isEmptyOrNull(offset) || offset.equals("-1"))
				objs = sellService.findAll(example);
	        else
	        	objs = sellService.findAll(example,appUtil.getPageRequest(0,Integer.valueOf(offset),appUtil.orderByDESC("sellId"))).getContent();

//			List<AgricultureIncome> objs = sellService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<SellDTO> dtos=new ArrayList<SellDTO>(); 
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				SellDTO dto = modelMapper.map(o, SellDTO.class);
				if(appUtil.notEmptyNorNull(o.getStock()) && appUtil.notEmptyNorNull(o.getStock().getItemId())) {
					Optional<Item> option = itemService.findById(o.getStock().getItemId());
					if(option.isPresent()) {
						Item item  = option.get();
						dto.setItemId(item.getId());
						dto.setItemName(item.getIname());
						dto.setItemCode(item.getIcode());
	//					dto.setStock(item.getStock());
					}
	//				Optional<Stock> option2 = stockService.findById(dto.getStockDTO()());
					if(o.getStock()!=null) {
						modelMapper.addConverter(appUtil.localDateToString);
						modelMapper.addConverter(appUtil.localDateTimeToString);
						StockDTO stockDTO = modelMapper.map(o.getStock(), StockDTO.class);
						dto.setStockDTO(stockDTO);
					}
					
	//				dto.setDatedStr(appUtil.getDateStr(o.getDated()));
	//				dto.setUpdated(appUtil.getDateStr(o.getUpdated()));
	//				dto.setIExpiry(appUtil.getLocalDateStr(o.getIExpiry()));
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
				Optional<Item> option = itemService.findById(obj.getItemId());
				SellDTO dtotemp = modelMapper.map(obj, SellDTO.class);
				if(option.isPresent()) {
					Item item  = option.get();
					dtotemp.setItemId(item.getId());
					dtotemp.setItemName(item.getIname());
					dtotemp.setStock(item.getStock());
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
	public GenericResponse addSell(@RequestBody final List<SellDTO> dtos, final HttpServletRequest request) {
		try {
//			ObjectMapper mapper = new ObjectMapper();
//			List<MappingIterator<SellDTO[]>> ppl2 = Arrays.asList(mapper.readValues((JsonParser) json, SellDTO[].class));
//			List<SellDTO> dtos = mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, SellDTO.class));
//			List<SellDTO> myObjects = mapper.readValue(json, new TypeReference<List<SellDTO>>(){});
//			SellDTO dto = dtos.get(0);
			if(appUtil.isEmptyOrNull(sellService.addSell(dtos))) {
				if(appUtil.isEmptyOrNull(dtos)) {
					return new GenericResponse(appUtil.FAILED,messages.getMessage(appUtil.FAILED, null,"msg.purchase.save.error", request.getLocale()));
				}else {
					return new GenericResponse(appUtil.FAILED,messages.getMessage(appUtil.FAILED,null,"msg.purchase.update.error",  request.getLocale()));
				}
			}else {
				if(appUtil.isEmptyOrNull(dtos)) {
					return new GenericResponse(appUtil.SUCCESS,messages.getMessage(appUtil.SUCCESS, null,"msg.purchase.saved", request.getLocale()));
				}else {
					return new GenericResponse(appUtil.SUCCESS,messages.getMessage(appUtil.SUCCESS,null, "msg.purchase.updated", request.getLocale()));
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addSell "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage(appUtil.ERROR,null,"message.error_system_error"+e.getMessage(), request.getLocale()),
					e.getCause().toString());
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
				Item item = itemService.getOne(obj.getItemId());
	        	Float stock = item.getStock()-obj.getQuantity();
				if(!appUtil.isEmptyOrNull(obj.getSellId())){
					Sell objTemp = sellService.getOne(obj.getSellId());
					if(objTemp.getQuantity() > obj.getQuantity())
						stock = item.getStock() - (obj.getQuantity() - objTemp.getQuantity());
					else
						stock = item.getStock() + (objTemp.getQuantity() - obj.getQuantity());
					
					item.setStock(stock);	
				}
				//updating stock
				item.setStock(stock);
		        itemService.save(item);
	//			//updating stock
//		        obj.setStock(stock);
		        obj.setDated(dated);
				obj.setUpdated(dated);
	
				obj = sellService.save(obj);
			});
			String status = sellService.createReport(objs);
			return new GenericResponse(status,messages.getMessage("message.userNotFound", null, request.getLocale()));
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
				
			Optional<Item> o = itemService.findById(dto.getItemId());
			if(!o.isPresent())
				return new GenericResponse("NOT_FOUND");
			
			Item item = o.get();
        	Float stock = Float.sum(item.getStock(),dto.getQuantity());
			//updating stock
			item.setStock(stock);
	        itemService.save(item);
	        
	        Sell s = sellService.getOne(dto.getSellId());
	        
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
}
