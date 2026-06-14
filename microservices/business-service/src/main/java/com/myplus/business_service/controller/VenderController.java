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
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.VenderDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@Controller
public class VenderController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IVenderService venderService;
	
	@Autowired
	ICompanyService companyService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	@Autowired ModelMapper modelMapper;

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}

	@RequestMapping(value = "/getUserVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserVender(final HttpServletRequest request) {
		try {
			List<Vender> objs = venderService.findScoped(orgId(), userId());
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VenderDTO> dtos=new ArrayList<VenderDTO>(); 
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				if(!appUtil.isEmptyOrNull(obj.getCompany())) {
					dto.setCompanyId(obj.getCompany().getId());
					dto.setCompanyName(obj.getCompany().getName());
				}
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserVender "+e.getCause(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getUserVenders", method = RequestMethod.GET)
	@ResponseBody
	public String getUserVenders(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<Vender> objs = venderService.findScoped(orgId(), userId());

			// objs.forEach(d -> {
			// 	if(d!=null && d.getId()!=null) {
			// 		sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			// 	}
			// });
		    // return sb.toString();

			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" +d.getName() + "</option>");
				// sb.append("<option value=" + d.getId() + ">" +d.getIcode()+" ~ "+d.getIname() + "</option>");
			});
			return sb.toString();

		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > getUserVenders "+e.getCause(), e);			
			return (sb.append("<option value=''>No Data found</option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllVender(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Vender> objs = venderService.findScoped(orgId(), userId());
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VenderDTO> dtos=new ArrayList<VenderDTO>();
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				if(obj.getCompany() != null) {
					dto.setCompanyId(obj.getCompany().getId());
					dto.setCompanyName(obj.getCompany().getName());
				}
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
			LOGGER.error(this.getClass().getName()+" > getAllVender "+e.getCause(), e);			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addVender", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final VenderDTO dto, final HttpServletRequest request) {
		try {
			Vender obj= new Vender();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				// dup-name check within the active tenant (was a userId-only Example probe)
				boolean exists = venderService.findScoped(orgId(), userId()).stream()
						.anyMatch(v -> v.getName()!=null && v.getName().equalsIgnoreCase(dto.getName()));
				if(exists)
					return new GenericResponse("FOUND", "Vender '" + dto.getName() + "' already exists.");
			}

			obj = modelMapper.map(dto, Vender.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				Vender existing = venderService.findById(dto.getId()).orElse(null);
				if(existing != null) obj.setDated(existing.getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj.setUserId(user.getUserId());                  // audit
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope

			obj.setCompany(companyService.getReferenceById(dto.getCompanyId()));
			obj = venderService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save vender. Please try again.");
			}else {
				return new GenericResponse("SUCCESS", "Vender saved successfully.");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > addVender "+e.getCause(), e);
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	@RequestMapping(value = "/deleteVender", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteVender( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					Long vid = Long.valueOf(id);
					Vender existing = venderService.findById(vid).orElse(null);
					if(existing == null) continue;
					if(inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						venderService.deleteById(vid);
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			LOGGER.error(this.getClass().getName()+" > deleteVender "+e.getCause(), e);			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
