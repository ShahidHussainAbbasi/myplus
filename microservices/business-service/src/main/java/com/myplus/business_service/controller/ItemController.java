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
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.dto.ItemDTO;
import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.Vender;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.IItemService;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;


@RestController
public class ItemController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());

	@Autowired
	private MessageSource messages;

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

	@RequestMapping(value = "/getUserItem", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItem(final HttpServletRequest request) {
		try {
			Item filterBy = new Item();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getUserId());
			Example<Item> example = Example.of(filterBy);
			List<Item> objs = itemService.findAll(example);
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
					Optional<Vender> vender = venderService.findById(dto.getVenderId());
					if(vender.isPresent()) {
						final Vender o = vender.get();
						dto.setVenderId(o.getId());
						dto.setVenderName(o.getName());
					}
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
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItem " + e.getCause());
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserItems", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItems(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Item filterBy = new Item();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getUserId());
			Example<Item> example = Example.of(filterBy);
			List<Item> objs = itemService.findAll(example);
			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" +d.getIname() + "</option>");
				// sb.append("<option value=" + d.getId() + ">" +d.getIcode()+" ~ "+d.getIname() + "</option>");
			});
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause());
			return (sb.append("<option value=''> Item not available </option>")).toString();
		}
	}

	@RequestMapping(value = "/getItem", method = RequestMethod.GET)
	@ResponseBody
	public Item getItem(@RequestParam final Long itemId) {
		try {
			if(appUtil.isEmptyOrNull(itemId))
				return null;
			
			return itemService.findById(itemId).orElse(null);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause());
			return null;
		}
	}

	@RequestMapping(value = "/getAllItem", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllItem(final HttpServletRequest request) {
		try {
			List<Item> objs = itemService.findAll();
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
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getAllItem " + e.getCause());
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/addItem", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItem(@Validated final ItemDTO dto, final HttpServletRequest request) {
		try {
			Item obj= new Item();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());			
			if(appUtil.isEmptyOrNull(dto.getId())){
//				obj.setUserId(user.getUserId());
				if(appUtil.notEmptyNorNull(dto.getIname())){
					obj.setIcode(dto.getIcode());
				}
				if(appUtil.notEmptyNorNull(dto.getIname())){
					obj.setIname(dto.getIname());
				}
				Example<Item> example = Example.of(obj);
				if(itemService.exists(example))
					return new GenericResponse("FOUND", "Item '" + dto.getIname() + "' already exists.");
			}

			modelMapper.addConverter(appUtil.stringToLocalDate);
			modelMapper.addConverter(appUtil.stringToLocalDateTime);
			obj = modelMapper.map(dto, Item.class);
			obj = itemService.save(obj);
			if (appUtil.isEmptyOrNull(obj.getId())) {
				return new GenericResponse("FAILED", "Failed to save item. Please try again.");
			} else {
				return new GenericResponse("SUCCESS", "Item saved successfully.");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > addItem " + e.getCause());
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}

	@RequestMapping(value = "/deleteItem", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteItem(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for (String id : idList) {
					itemService.deleteById(Long.valueOf(id));
				}
				return true;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),"SUCCESS");
			} else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
								// request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > deleteItem " + e.getCause());
			return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),
		}
	}
}
