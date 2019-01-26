package com.web.controller.business;

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

import com.persistence.Repo.DoctorRepository;
import com.persistence.Repo.business.CompanyRepo;
import com.persistence.model.User;
import com.persistence.model.business.Company;
import com.security.ActiveUserStore;
import com.service.IAppointmentService;
import com.service.UserService;
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
	ActiveUserStore activeUserStore;

	@Autowired
	IAppointmentService appointmentService;

	@Autowired
	CompanyRepo companyRepo;

	@Autowired
	DoctorRepository doctorRepository;

	@Autowired
	UserService userService;
	
	@Autowired
	RequestUtil requestUtil;

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
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),companies);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),companies);
			}
		} catch (Exception e) {
			e.printStackTrace();
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
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<Company> example = Example.of(filterBy);
			List<Company> companies = companyRepo.findAll(example);
			sb.append("<option data-tokens=''> Select OwnerDTO </option>");
			companies.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getName()+">"+d.getName()+"</option>");
//				sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllCompany", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllCompanies(final HttpServletRequest request) {
		try {
			List<Company> companies = companyRepo.findAll();
			if(companies.size()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),companies);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),companies);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addCompany", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addCompany(@Validated final CompanyDTO companyDTO, final HttpServletRequest request) {
		try {
			Company company = new Company();
			User user = requestUtil.getCurrentUser();
			companyDTO.setUserId(user.getId());
			companyDTO.setUserType(user.getUserType());
			company = modelMapper.map(companyDTO, Company.class);
			if(company.getId()!=null && company.getId()>0) {
				Example<Company> example = Example.of(company);
				if(companyRepo.exists(example)) {
					return new GenericResponse("FOUND",messages.getMessage("Your vender already exist", null, request.getLocale()));
				}
			}
			company.setDated(AppUtil.todayDateStr());
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
/*			OwnerDTO company = new OwnerDTO();
			company.setName(companyDTO.getName());
			Example<OwnerDTO> example = Example.of(company);
			company = companyRepo.findOne(example).get();
			companyDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			companyDTO.setDated(AppUtil.todayDateStr());
*/			
			companyDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			companyDTO.setDated(AppUtil.todayDateStr());
			Company company = modelMapper.map(companyDTO, Company.class);
			Company companyTemp = companyRepo.saveAndFlush(company);
			if(companyTemp.getId()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("OwnerDTO updated success", null, request.getLocale()));
			}else {
				return new GenericResponse("FAILED",messages.getMessage("OwnerDTO updated fail", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("OwnerDTO update error", null, request.getLocale()),
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
