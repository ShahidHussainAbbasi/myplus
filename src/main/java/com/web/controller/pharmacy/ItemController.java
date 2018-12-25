package com.web.controller.pharmacy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

import com.persistence.Repo.pharmacy.ItemRepo;
import com.persistence.Repo.pharmacy.ItemTypeRepo;
import com.persistence.Repo.pharmacy.ItemUnitRepo;
import com.persistence.model.pharmacy.ItemUnit;
import com.persistence.model.pharmacy.Item;
import com.persistence.model.pharmacy.ItemType;
import com.persistence.model.pharmacy.ItemUnit;
import com.security.ActiveUserStore;
import com.service.UserService;
import com.web.dto.pharmacy.ItemDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;

@Controller
public class ItemController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;
	@Autowired
	ActiveUserStore activeUserStore;

	@Autowired
	ItemRepo itemRepo;

	@Autowired
	ItemTypeRepo itemTypeRepo;

	@Autowired
	ItemUnitRepo itemUnitRepo;

	@Autowired
	UserService userService;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserItem", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItem(final HttpServletRequest request) {
		try {
			Item filterBy = new Item();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<Item> example = Example.of(filterBy);
			List<Item> items = itemRepo.findAll(example);
			if(items.size()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",items);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",items);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserItemType", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItemType(final HttpServletRequest request) {
		try {
			ItemType filterBy = new ItemType();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<ItemType> example = Example.of(filterBy);
			List<ItemType> items = itemTypeRepo.findAll(example);
			if(items.size()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",items);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",items);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserItemTypes", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItemTypes(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			ItemType filterBy = new ItemType();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<ItemType> example = Example.of(filterBy);
			List<ItemType> venders = itemTypeRepo.findAll(example);
			Set<String> items = new HashSet<>();  
			venders.forEach(d -> {
				if(d!=null)
					items.add(d.getName());
				
			});
			sb.append("<option value='-1'> Select Item Type </option>");
			items.forEach(d -> {
				if(d!=null)
					sb.append("<option value="+d+">"+((d!=null && d!="")?d:"-")+"</option>");
				
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getUserItemUnits", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItemUnits(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			ItemUnit filterBy = new ItemUnit();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<ItemUnit> example = Example.of(filterBy);
			List<ItemUnit> venders = itemUnitRepo.findAll(example);
			Set<String> items = new HashSet<>();  
			venders.forEach(d -> {
				if(d!=null)
					items.add(d.getName());
				
			});
			sb.append("<option value='-1'> Select Item Unit </option>");
			items.forEach(d -> {
				if(d!=null)
					sb.append("<option value="+d+">"+((d!=null && d!="")?d:"-")+"</option>");
				
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getUserItemUnit", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUsertemUnit(final HttpServletRequest request) {
		try {
			ItemUnit filterBy = new ItemUnit();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<ItemUnit> example = Example.of(filterBy);
			List<ItemUnit> items = itemUnitRepo.findAll(example);
			if(items.size()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",items);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",items);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/addItem", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItem(@Validated final ItemDTO itemDTO, final HttpServletRequest request) {
		try {
			Item item = new Item();
			item.setName(itemDTO.getName());
			Example<Item> example = Example.of(item);
			if(itemRepo.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("Item "+"already.exist", null, request.getLocale()));
			}

			itemDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			itemDTO.setDated(AppUtil.todayDateStr());
			item = modelMapper.map(itemDTO, Item.class);
			Item itemTemp = itemRepo.save(item);
			if(itemTemp.getId()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",itemTemp);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"FAILED",itemTemp);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addItemType", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItemType(@Validated final ItemType itemType, final HttpServletRequest request) {
		try {
			ItemType itemTypeTemp = new ItemType();
			itemTypeTemp.setName(itemType.getName());
			Example<ItemType> example = Example.of(itemTypeTemp);
			if(itemTypeRepo.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("Item Type "+"already.exist", null, request.getLocale()));
			}

			itemType.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			itemType.setDated(AppUtil.todayDateStr());
			ItemType itemUnitTemp = itemTypeRepo.save(itemType);
			if(itemUnitTemp.getId()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",itemUnitTemp);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"FAILED",itemUnitTemp);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/addItemUnit", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItemUnit(@Validated final ItemUnit itemUnit, final HttpServletRequest request) {
		try {
			ItemUnit itemUnitTemp = new ItemUnit();
			itemUnitTemp.setName(itemUnit.getName());
			Example<ItemUnit> example = Example.of(itemUnitTemp);
			if(itemUnitRepo.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("Item Unit "+"already.exist", null, request.getLocale()));
			}

			itemUnit.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			itemUnit.setDated(AppUtil.todayDateStr());
			ItemUnit itemUnitType = itemUnitRepo.save(itemUnit);
			if(itemUnitType.getId()>0) {
				return new GenericResponse("SUCCESS",itemUnitType);
			}else {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()));
		}
	}

	@RequestMapping(value = "/deleteItem", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteItem( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					itemRepo.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
//			companyRepo.deleteById(id);delete(company);
		} catch (Exception e) {
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}

	@RequestMapping(value = "/deleteItemType", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteItemType( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					itemTypeRepo.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
//			companyRepo.deleteById(id);delete(company);
		} catch (Exception e) {
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}

	@RequestMapping(value = "/deleteItemUnit", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteItemUnit( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					itemUnitRepo.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
//			companyRepo.deleteById(id);delete(company);
		} catch (Exception e) {
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
