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
import com.myplus.business_service.entity.ItemType;
import com.myplus.business_service.service.IItemTypeService;
import com.myplus.business_service.dto.ItemTypeDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

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
    
	@Autowired ModelMapper modelMapper;

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}

	@RequestMapping(value = "/getUserItemType", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserItemType(final HttpServletRequest request) {
		try {
			List<ItemType> objs = itemTypeService.findScoped(orgId(), userId());

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
			LOGGER.error(this.getClass().getName() + " > getUserItemType " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),e.getMessage());
		}
	}

	@RequestMapping(value = "/getUserItemTypes", method = RequestMethod.GET)
	@ResponseBody
	public String getUserItemTypes(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<ItemType> objs = itemTypeService.findScoped(orgId(), userId());

			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" + d.getName() + "</option>");
			});
			return sb.toString();
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > getUserItems " + e.getCause(), e);
			return (sb.append("<option value=''> Item not available </option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllItemType", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllItemType(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<ItemType> objs = itemTypeService.findScoped(orgId(), userId());
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
			LOGGER.error(this.getClass().getName() + " > getAllItem " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}

	@RequestMapping(value = "/addItemType", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addItemType(@Validated final ItemTypeDTO dto, final HttpServletRequest request) {
		try {
			ItemType obj= new ItemType();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				// dup-name check within the active tenant (was a userId-only Example probe)
				boolean exists = itemTypeService.findScoped(orgId(), userId()).stream()
						.anyMatch(t -> t.getName()!=null && t.getName().equalsIgnoreCase(dto.getName()));
				if(exists)
					return new GenericResponse("FOUND", "The Item Type '"+dto.getName()+"' already exists.");
			}

			obj = modelMapper.map(dto, ItemType.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj.setDated(itemTypeService.getOne(dto.getId()).getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj.setUserId(user.getUserId());                  // audit
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope

			obj = itemTypeService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName() + " > addItem " + e.getCause(), e);
			return new GenericResponse("ERROR", messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getMessage());
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
					Long tid = Long.valueOf(id);
					ItemType existing = itemTypeService.findById(tid).orElse(null);
					if (existing == null) continue;
					if (inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						itemTypeService.deleteById(tid);
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
