package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.entity.ItemUnit;
import com.myplus.business_service.service.IItemUnitService;
import com.myplus.business_service.dto.ItemUnitDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

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

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}

	@RequestMapping(value = "/getUserItemUnit", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItemUnit(final HttpServletRequest request) {
		try {
			List<ItemUnit> objs = itemUnitService.findScoped(orgId(), userId());

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
			LOGGER.error(this.getClass().getName() + " > getUserItemUnit " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),e.getMessage());
		}
	}

	@RequestMapping(value = "/getUserItemUnits", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItemUnits(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<ItemUnit> objs = itemUnitService.findScoped(orgId(), userId());

			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
			});
			return sb.toString();
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause(), e);
			return (sb.append("<option value=''> Item not available </option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllItemUnit", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllItemUnit(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<ItemUnit> objs = itemUnitService.findScoped(orgId(), userId());
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
			LOGGER.error(this.getClass().getName() + " > getAllItem " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}

	@RequestMapping(value = "/addItemUnit", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItemUnit(@Validated final ItemUnitDTO dto, final HttpServletRequest request) {
		try {
			ItemUnit obj= new ItemUnit();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				// dup-name check within the active tenant (was a userId-only Example probe)
				boolean exists = itemUnitService.findScoped(orgId(), userId()).stream()
						.anyMatch(u -> u.getName()!=null && u.getName().equalsIgnoreCase(dto.getName()));
				if(exists)
					return new GenericResponse("FOUND", "Item unit '" + dto.getName() + "' already exists.");
			}

			obj = modelMapper.map(dto, ItemUnit.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj.setDated(itemUnitService.getOne(dto.getId()).getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj.setUserId(user.getUserId());                  // audit
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope

			obj = itemUnitService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save item unit. Please try again.");
			}else {
				return new GenericResponse("SUCCESS", "Item unit saved successfully.");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > addItemUnit " + e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
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
					Long uid = Long.valueOf(id);
					ItemUnit existing = itemUnitService.findById(uid).orElse(null);
					if (existing == null) continue;
					if (inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						itemUnitService.deleteById(uid);
				}
				return true;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),"SUCCESS");
			} else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
								// request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > deleteItem " + e.getCause(), e);
			return false;// new GenericResponse(messages.getMessage("message.userNotFound", null,
							// request.getLocale()),
		}
	}
}
