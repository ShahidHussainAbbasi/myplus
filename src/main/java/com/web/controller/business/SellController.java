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
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.business.Item;
import com.persistence.model.business.Purchase;
import com.persistence.model.business.Sell;
import com.service.business.ICustomerService;
import com.service.business.IItemService;
import com.service.business.IItemTypeService;
import com.service.business.IItemUnitService;
import com.service.business.IPurchaseService;
import com.service.business.ISellService;
import com.service.business.IVenderService;
import com.web.dto.business.SellDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
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

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserSell(final HttpServletRequest request) {
		try {
			Sell filterBy = new Sell();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Sell> example = Example.of(filterBy);
			List<Sell> objs = sellService.findAll(example);
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<SellDTO> dtos=new ArrayList<SellDTO>(); 
			objs.forEach(obj ->{
				SellDTO dto = modelMapper.map(obj, SellDTO.class);
//				dto.setCustomerId(obj.getCustomer().getId());
//				dto.setCustomerName(obj.getCustomer().getName());
//				dto.setItemUnitId(obj.getItemUnit().getId());
//				dto.setItemUnitName(obj.getItemUnit().getName());
//				dto.setItemTypeId(obj.getItemType().getId());
//				dto.setItemTypeName(obj.getItemType().getName());
				Optional<Item> option = itemService.findById(dto.getItemId());
				if(option.isPresent()) {
					Item item  = option.get();
					dto.setItemId(item.getId());
					dto.setItemName(item.getName());
					dto.setStock(item.getStock());
				}
//				Item item = itemService.getOne(dto.getItemId());
//				dto.setItemId(item.getId());
//				dto.setItemName(item.getName());
//				dto.setStock(item.getStock());
				dto.setDatedStr(AppUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserSell "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getAllSell", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSell(final HttpServletRequest request) {
		try {
			List<Sell> objs = sellService.findAll();
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			
			List<SellDTO> dtos=new ArrayList<SellDTO>(); 
			objs.forEach(obj ->{
				SellDTO dto = modelMapper.map(obj, SellDTO.class);
//				dto.setCustomerId(obj.getCustomer().getId());
//				dto.setCustomerName(obj.getCustomer().getName());
//				dto.setItemUnitId(obj.getItemUnit().getId());
//				dto.setItemUnitName(obj.getItemUnit().getName());
//				dto.setItemTypeId(obj.getItemType().getId());
//				dto.setItemTypeName(obj.getItemType().getName());
				
				dto.setDatedStr(AppUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(AppUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getAllSell "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addSell", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addSell(@Validated final SellDTO dto, final HttpServletRequest request) {
		try {
			Sell obj= new Sell();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			obj = modelMapper.map(dto, Sell.class);
			obj.setUserId(user.getId());
			//if it is update

			//if update
			Item item = itemService.getOne(dto.getItemId());
        	Float stock = item.getStock()-dto.getQuantity();
			if(!AppUtil.isEmptyOrNull(dto.getId())){
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
//	        Item item = itemService.getOne(dto.getItemId());
//	        Float stock = (item.getStock() - dto.getQuantity());
//	        item.setStock(stock);
//	        itemService.save(item);
	        obj.setStock(stock);
	        obj.setDated(dated);
			obj.setUpdated(dated);
			//add customer
//			if(!AppUtil.isEmptyOrNull(dto.getCustomerId()))
//				obj.setCustomer(customerService.getOne(dto.getCustomerId()));
//			
//			if(!AppUtil.isEmptyOrNull(dto.getItemTypeId()))
//				obj.setItemType(itemTypeService.getOne(dto.getItemTypeId()));
//
//			if(!AppUtil.isEmptyOrNull(dto.getItemUnitId()))
//				obj.setItemUnit(itemUnitService.getOne(dto.getItemUnitId()));
			
			obj = sellService.save(obj);
			if(AppUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addSell "+e.getCause());			
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
			LOGGER.error(this.getClass().getName()+" > deleteSell "+e.getCause());			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
