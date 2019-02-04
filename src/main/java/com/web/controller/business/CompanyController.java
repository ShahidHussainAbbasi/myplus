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
import com.persistence.model.business.Company;
import com.service.business.ICompanyService;
import com.web.dto.business.CompanyDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

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
	
	@RequestMapping(value = "/getUserCompany", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserCompany(final HttpServletRequest request) {
		try {
			Company filterBy = new Company();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Company> example = Example.of(filterBy);
			List<Company> objs = companyService.findAll(example);
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<CompanyDTO> dtos=new ArrayList<CompanyDTO>(); 
			objs.forEach(obj ->{
				CompanyDTO dto = modelMapper.map(obj, CompanyDTO.class);
				dto.setDatedStr(AppUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserCompany "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserCompanies", method = RequestMethod.GET)
	@ResponseBody
	public String getUserCompanies(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Company filterBy = new Company();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Company> example = Example.of(filterBy);
			List<Company> objs = companyService.findAll(example);
			
			objs.forEach(d -> {
				sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
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
			List<Company> objs = companyService.findAll();
			if(AppUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getAllCompany "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addCompany", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final CompanyDTO dto, final HttpServletRequest request) {
		try {
			Company obj= new Company();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			
			Example<Company> example = Example.of(obj);
			if(AppUtil.isEmptyOrNull(dto.getId())){
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				if(companyService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Company "+dto.getName()+" already exist", null, request.getLocale()));				
			}
			
			obj = modelMapper.map(dto, Company.class);
			//if it is update
			if(!AppUtil.isEmptyOrNull(dto.getId())) {
				obj.setDated(companyService.getOne(dto.getId()).getDated());
//				dated = obj.getDated();
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);

			obj = companyService.save(obj);
			if(AppUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addCompany "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
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
					companyService.deleteById(Long.valueOf(id));
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
