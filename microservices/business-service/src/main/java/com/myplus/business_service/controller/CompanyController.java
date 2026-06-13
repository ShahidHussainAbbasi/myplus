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

import com.myplus.business_service.dto.CompanyDTO;
import com.myplus.business_service.entity.Company;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;

@Controller
public class CompanyController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ICompanyService companyService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();

	private Long userId() { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getUserId(); }
	/** Active tenant the request is scoped to (from the gateway's X-Org-Id header). */
	private Long orgId()  { AuthenticatedUser u = requestUtil.getCurrentUser(); return u==null?null:u.getOrganizationId(); }
	private boolean inMyTenant(Long rowOrg, Long rowUser) {
		return (rowOrg != null && rowOrg.equals(orgId()))
			|| (rowOrg == null && rowUser != null && rowUser.equals(userId()));
	}

	@RequestMapping(value = "/getUserCompany", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserCompany(final HttpServletRequest request) {
		try {
			List<Company> objs = companyService.findScoped(orgId(), userId());
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<CompanyDTO> dtos=new ArrayList<CompanyDTO>(); 
			objs.forEach(obj ->{
				CompanyDTO dto = modelMapper.map(obj, CompanyDTO.class);
				dto.setDatedStr(appUtil.getLocalDateTimeStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getLocalDateTimeStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserCompany "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getUserCompanies", method = RequestMethod.GET)
	@ResponseBody
	public String getUserCompanies(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			List<Company> objs = companyService.findScoped(orgId(), userId());

			// objs.forEach(d -> {
			// 	sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			// });
		    // return sb.toString();

			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" +d.getName() + "</option>");
				// sb.append("<option value=" + d.getId() + ">" +d.getIcode()+" ~ "+d.getIname() + "</option>");
			});
			return sb.toString();

		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserCompanies "+e.getCause());			
			return (sb.append("<option value=''>No Data found</option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllCompany", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllCompany(final HttpServletRequest request) {
		try {
			// was findAll() — cross-tenant leak; now scoped to the active org.
			List<Company> objs = companyService.findScoped(orgId(), userId());
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getAllCompany "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getMessage());
		}
	}
	
	@RequestMapping(value = "/addCompany", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final CompanyDTO dto, final HttpServletRequest request) {
		try {
			Company obj= new Company();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());

			if(appUtil.isEmptyOrNull(dto.getId())){
				// dup-name check within the active tenant (was a userId-only Example probe)
				boolean exists = companyService.findScoped(orgId(), userId()).stream()
						.anyMatch(c -> c.getName()!=null && c.getName().equalsIgnoreCase(dto.getName()));
				if(exists) {
					return new GenericResponse("FOUND", "Company '" + dto.getName() + "' already exists.");
				}
			}

			obj = modelMapper.map(dto, Company.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				Company existing = companyService.findById(dto.getId()).orElse(null);
				if(existing != null) obj.setDated(existing.getDated());
			}else {
				// obj.setCreatedAt handled by @PrePersist
			}
			obj.setUserId(user.getUserId());                  // audit
			obj.setOrganizationId(user.getOrganizationId());  // tenant scope
			// obj.setUpdatedAt handled by @PreUpdate
			obj = companyService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save company. Please try again.");
			}else {
				return new GenericResponse("SUCCESS", "Company saved successfully.");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addCompany "+e.getCause());
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	@RequestMapping(value = "/deleteCompany", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteCompany( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					Long cid = Long.valueOf(id);
					Company existing = companyService.findById(cid).orElse(null);
					if(existing == null) continue;
					if(inMyTenant(existing.getOrganizationId(), existing.getUserId())) // anti-IDOR
						companyService.deleteById(cid);
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > deleteCompany "+e.getCause());			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
