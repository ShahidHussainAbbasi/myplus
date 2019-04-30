package com.web.controller.business;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
import com.persistence.model.business.ItemType;
import com.service.business.IItemTypeService;
import com.web.dto.business.ItemTypeDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class ItemTypeController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IItemTypeService itemTypeService;

	@Autowired
	RequestUtil requestUtil;

    @Autowired
    private AppUtil appUtil;  
    
	ModelMapper modelMapper = new ModelMapper();

	@RequestMapping(value = "/getUserItemType", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItemType(final HttpServletRequest request) {
		try {
			ItemType filterBy = new ItemType();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			Example<ItemType> example = Example.of(filterBy);
			List<ItemType> objs = itemTypeService.findAll(example);

			List<ItemTypeDTO> dtos = new ArrayList<ItemTypeDTO>();
			objs.forEach(obj -> {
				ItemTypeDTO dto = modelMapper.map(obj, ItemTypeDTO.class);
				dto.setName(obj.getName());
				dto.setDescription(obj.getDescription());

				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItemType " + e.getCause());
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserItemTypes", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItemTypes(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			ItemType filterBy = new ItemType();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			Example<ItemType> example = Example.of(filterBy);
			List<ItemType> objs = itemTypeService.findAll(example);

			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
			});
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause());
			return (sb.append("<option value=''> Item not available </option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllItemType", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllItemType(final HttpServletRequest request) {
		try {
			List<ItemType> objs = itemTypeService.findAll();
			if (appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",
						messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<ItemTypeDTO> dtos = new ArrayList<ItemTypeDTO>();
			objs.forEach(obj -> {
				ItemTypeDTO dto = modelMapper.map(obj, ItemTypeDTO.class);
				dto.setName(obj.getName());
				dto.setDescription(obj.getDescription());

				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()), objs);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getAllItem " + e.getCause());
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/addItemType", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItemType(@Validated final ItemTypeDTO dto, final HttpServletRequest request) {
		try {
			ItemType obj= new ItemType();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			obj.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				Example<ItemType> example = Example.of(obj);
				if(itemTypeService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Vender "+dto.getName()+" already exist", null, request.getLocale()));
			}
			
			obj = modelMapper.map(dto, ItemType.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj.setDated(itemTypeService.getOne(dto.getId()).getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);

			obj = itemTypeService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > addItem " + e.getCause());
			return new GenericResponse("ERROR", messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/deleteItemType", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteItemType(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for (String id : idList) {
					itemTypeService.deleteById(Long.valueOf(id));
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
