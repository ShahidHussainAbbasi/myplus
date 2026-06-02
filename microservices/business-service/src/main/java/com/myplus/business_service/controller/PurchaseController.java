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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.security.AuthenticatedUser;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.IItemService;
// import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IPurchaseService;
import com.myplus.business_service.service.IStockService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.PurchaseDTO;
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
	IItemService itemService;

	@Autowired
	IStockService stockService;

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
			AuthenticatedUser user = requestUtil.getCurrentUser();
			obj.setUserId(user.getUserId());
	        Example<Purchase> example = Example.of(obj);
			List<Purchase> objs = purchaseService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<PurchaseDTO> dtos=new ArrayList<PurchaseDTO>(); 
			objs.forEach(o ->{
				modelMapper.addConverter(appUtil.localDateTimeToString);
				modelMapper.addConverter(appUtil.localDateToString);
				PurchaseDTO dto = modelMapper.map(o, PurchaseDTO.class);
				if(appUtil.notEmptyNorNull(o.getStock()) && appUtil.notEmptyNorNull(o.getStock().getItemId())) {
					Optional<Item> option = itemService.findById(o.getStock().getItemId());
					if(option.isPresent()) {
						Item item = option.get();
						dto.setItemId(item.getId());
						dto.setIname(item.getIname());
						dto.setIcode(item.getIcode());
						// dto.setDescription(item.getIdesc());
//						dto.setStock(item.getStock());
					}
//					Optional<Stock> option2 = stockService.findById(dto.getStockDTO()());
					if(o.getStock()!=null) {
						modelMapper.addConverter(appUtil.localDateToString);
						modelMapper.addConverter(appUtil.localDateTimeToString);
						StockDTO stock = modelMapper.map(o.getStock(), StockDTO.class);
						dto.setStock(stock);
					}
					
//					dto.setDatedStr(appUtil.getDateStr(o.getDated()));
//					dto.setUpdated(appUtil.getDateStr(o.getUpdated()));
//					dto.setIExpiry(appUtil.getLocalDateStr(o.getIExpiry()));
					dtos.add(dto);
				}
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
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addPurchase "+e.getCause());
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
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
