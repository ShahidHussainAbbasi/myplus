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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.Repo.DoctorRepository;
import com.persistence.Repo.pharmacy.CompanyRepo;
import com.persistence.model.pharmacy.Company;
import com.security.ActiveUserStore;
import com.service.IAppointmentService;
import com.service.UserService;
import com.web.dto.pharmacy.CompanyDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;

@Controller
public class CompanyController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;
	@Autowired
	ActiveUserStore activeUserStore;

	@Autowired
	IAppointmentService appointmentService;

	@Autowired
	CompanyRepo companyRepo;

	@Autowired
	DoctorRepository doctorRepository;

	@Autowired
	UserService userService;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserCompany", method = RequestMethod.GET)
	@ResponseBody
//	@PreAuthorize("hasRole('ADMIN')")
	public GenericResponse getUserCompany(final HttpServletRequest request) {
		try {
			Company filterBy = new Company();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<Company> example = Example.of(filterBy);
			List<Company> companies = companyRepo.findAll(example);
			if(companies.size()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",companies);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",companies);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserCompanies", method = RequestMethod.GET)
	@ResponseBody
	public String getUserCompanies(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Company filterBy = new Company();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<Company> example = Example.of(filterBy);
			List<Company> companies = companyRepo.findAll(example);
			Set<String> items = new HashSet<>();  
			companies.forEach(d -> {
				if(d!=null)
					items.add(d.getName());
				
			});
			sb.append("<option value='-1'> Select Company </option>");
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

	//	@RequestMapping(value = "/getAllCompany", method = RequestMethod.GET)
//	@ResponseBody
//	public GenericResponse getAllCompanies(final HttpServletRequest request) {
//		try {
//			List<Company> companies = companyRepo.findAll();
//			if(companies.size()>0) {
//				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",companies);
//			}else {
//				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",companies);
//			}
//		} catch (Exception e) {
//			e.printStackTrace();
//			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
//					e.getCause().toString());
//		}
//	}
	
	@RequestMapping(value = "/addCompany", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addCompany(@Validated final CompanyDTO companyDTO, final HttpServletRequest request) {
		try {
			Company company = new Company();
/*			company.setName(companyDTO.getName());
			company.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			Example<Company> example = Example.of(company);
			if(companyRepo.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("Company "+"already.exist", null, request.getLocale()));
			}
*/			
			companyDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			companyDTO.setDated(AppUtil.todayDateStr());
			company = modelMapper.map(companyDTO, Company.class);
			Company companyTemp = companyRepo.save(company);
			if(companyTemp.getId()>0) {
				return new GenericResponse("SUCCESS",companyTemp);
			}else {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/updateCompany", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse updateCompany(@Validated final CompanyDTO companyDTO, final HttpServletRequest request) {
		try {
/*			Company company = new Company();
			company.setName(companyDTO.getName());
			Example<Company> example = Example.of(company);
			company = companyRepo.findOne(example).get();
			companyDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			companyDTO.setDated(AppUtil.todayDateStr());
*/			
			companyDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			companyDTO.setDated(AppUtil.todayDateStr());
			Company company = modelMapper.map(companyDTO, Company.class);
			Company companyTemp = companyRepo.saveAndFlush(company);
			if(companyTemp.getId()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("Company updated success", null, request.getLocale()));
			}else {
				return new GenericResponse("FAILED",messages.getMessage("Company updated fail", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("Company update error", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/deleteCompany", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteClient( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					companyRepo.deleteById(Long.valueOf(id));
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
