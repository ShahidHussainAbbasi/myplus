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
import com.persistence.model.business.ItemUnit;
import com.service.business.IItemUnitService;
import com.web.dto.business.ItemUnitDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class ItemUnitController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IItemUnitService itemUnitService;

	@Autowired
	RequestUtil requestUtil;

    @Autowired
    private AppUtil appUtil;  
    
	ModelMapper modelMapper = new ModelMapper();

	@RequestMapping(value = "/getUserItemUnit", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItemUnit(final HttpServletRequest request) {
		try {
			ItemUnit filterBy = new ItemUnit();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			Example<ItemUnit> example = Example.of(filterBy);
			List<ItemUnit> objs = itemUnitService.findAll(example);

			List<ItemUnitDTO> dtos = new ArrayList<ItemUnitDTO>();
			objs.forEach(obj -> {
				ItemUnitDTO dto = modelMapper.map(obj, ItemUnitDTO.class);
				dto.setName(obj.getName());
				dto.setDescription(obj.getDescription());

				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()), dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > getUserItemUnit " + e.getCause());
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserItemUnits", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItemUnits(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			ItemUnit filterBy = new ItemUnit();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			Example<ItemUnit> example = Example.of(filterBy);
			List<ItemUnit> objs = itemUnitService.findAll(example);

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

	@RequestMapping(value = "/getAllItemUnit", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllItemUnit(final HttpServletRequest request) {
		try {
			List<ItemUnit> objs = itemUnitService.findAll();
			if (appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<ItemUnitDTO> dtos = new ArrayList<ItemUnitDTO>();
			objs.forEach(obj -> {
				ItemUnitDTO dto = modelMapper.map(obj, ItemUnitDTO.class);
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

	@RequestMapping(value = "/addItemUnit", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItemUnit(@Validated final ItemUnitDTO dto, final HttpServletRequest request) {
		try {
			ItemUnit obj= new ItemUnit();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			obj.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				Example<ItemUnit> example = Example.of(obj);
				if(itemUnitService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Item Unit "+dto.getName()+" already exist", null, request.getLocale()));
			}
			
			obj = modelMapper.map(dto, ItemUnit.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj.setDated(itemUnitService.getOne(dto.getId()).getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);

			obj = itemUnitService.save(obj);
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

	@RequestMapping(value = "/deleteItemUnit", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteItemUnit(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for (String id : idList) {
					itemUnitService.deleteById(Long.valueOf(id));
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
