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
import com.service.business.ICompanyService;
import com.service.business.IItemService;
import com.service.business.IItemTypeService;
import com.service.business.IItemUnitService;
import com.service.business.IPurchaseService;
import com.service.business.IVenderService;
import com.web.dto.business.PurchaseDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class PurchaseController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IPurchaseService purchaseService;
	
	@Autowired
	ICompanyService companyService;

	@Autowired
	IItemTypeService itemTypeService;

	@Autowired
	IItemUnitService itemUnitService;

	@Autowired
	IItemService itemService;

	@Autowired
	IVenderService venderService;

	@Autowired
	RequestUtil requestUtil;

    @Autowired
    private AppUtil appUtil;  
    
	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserPurchase", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserPurchase(final HttpServletRequest request) {
		try {
			Purchase obj = new Purchase();
			User user = requestUtil.getCurrentUser();
			obj.setUserId(user.getId());
	        Example<Purchase> example = Example.of(obj);
			List<Purchase> objs = purchaseService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>(); 
			objs.forEach(o ->{
				PurchaseDTO dto = modelMapper.map(o, PurchaseDTO.class);
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
				dto.setDatedStr(appUtil.getDateStr(o.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(o.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserPurchase "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getAllPurchase", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllPurchase(final HttpServletRequest request) {
		try {
			List<Purchase> objs = purchaseService.findAll();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			
			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>(); 
			objs.forEach(obj ->{
				PurchaseDTO dto = modelMapper.map(obj, PurchaseDTO.class);
//				dto.setItemUnitId(obj.getItemUnit().getId());
//				dto.setItemUnitName(obj.getItemUnit().getName());
//				dto.setItemTypeId(obj.getItemType().getId());
//				dto.setItemTypeName(obj.getItemType().getName());
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getAllPurchase "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addPurchase", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addPurchase(@Validated final PurchaseDTO dto, final HttpServletRequest request) {
		try {
			Purchase obj= new Purchase();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			obj = modelMapper.map(dto, Purchase.class);
			obj.setUserId(user.getId());

			obj.setDated(dated);
			obj.setUpdated(dated);
			
			obj.setItemId(dto.getItemId());
			//if update
			Item item = itemService.getOne(dto.getItemId());
        	Float stock = item.getStock()+dto.getQuantity();
			if(!appUtil.isEmptyOrNull(dto.getId())){
				Purchase objTemp = purchaseService.getOne(dto.getId());
				if(objTemp.getQuantity() > dto.getQuantity())
					stock = item.getStock() + (dto.getQuantity() - objTemp.getQuantity());
				else
					stock = item.getStock() - (objTemp.getQuantity() - dto.getQuantity());
				
				item.setStock(stock);	
			}
			item.setStock(stock);
			//updating stock
	        itemService.save(item);
			obj.setStock(stock);
			obj = purchaseService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addPurchase "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deletePurchase", method = RequestMethod.POST)
	@ResponseBody
	public boolean deletePurchase( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					purchaseService.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > deletePurchase "+e.getCause());			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
