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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.myplus.business_service.security.AuthenticatedUser;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.service.ICustomerService;
// import com.myplus.business_service.service.ICustomerService;
import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

@Controller
public class CustomerController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ICustomerService customerService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserCustomer", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserCustomer(final HttpServletRequest request) {
		try {
			Customer filterBy = new Customer();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getUserId());
	        Example<Customer> example = Example.of(filterBy);
			List<Customer> objs = customerService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<CustomerDTO> dtos=new ArrayList<CustomerDTO>(); 
			objs.forEach(obj ->{
				modelMapper.addConverter(appUtil.localDateToString);
				modelMapper.addConverter(appUtil.localDateTimeToString);
				CustomerDTO dto = modelMapper.map(obj, CustomerDTO.class);
				// dto.setDatedStr(appUtil.getLocalDateTimeStr(obj.getDated()));
				// dto.setUpdatedStr(appUtil.getLocalDateTimeStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserCustomer "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserCustomers", method = RequestMethod.GET) 
	@ResponseBody
	public String getUserCustomers(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Customer filterBy = new Customer();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getUserId());
	        Example<Customer> example = Example.of(filterBy);
			List<Customer> objs = customerService.findAll(example);
			
			objs.forEach(d -> {
				sb.append("<option value="+d.getCustomerId()+">"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserCustomers "+e.getCause());			
			return (sb.append("<option value=''>No Data found</option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllCustomer", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllCustomer(final HttpServletRequest request) {
		try {
			List<Customer> objs = customerService.findAll();
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getAllCustomer "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addCustomer", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final CustomerDTO dto, final HttpServletRequest request) {
		try {
			Customer obj= new Customer();
			LocalDateTime dated = LocalDateTime.now();
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			
			Example<Customer> example = Example.of(obj);
			if(appUtil.isEmptyOrNull(dto.getCustomerId())){
				obj.setUserId(user.getUserId());
				obj.setName(dto.getName());
				if(customerService.exists(example)) {
					return new GenericResponse("FOUND", "Customer '" + dto.getName() + "' already exists.");
				}
			}

			obj = modelMapper.map(dto, Customer.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getCustomerId())) {
				obj.setDated(customerService.getReferenceById(dto.getCustomerId()).getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj = customerService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save customer. Please try again.");
			}else {
				return new GenericResponse("SUCCESS", "Customer saved successfully.");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addCustomer "+e.getCause());
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
		}
	}
	
	@RequestMapping(value = "/deleteCustomer", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteCustomer(HttpServletRequest req, HttpServletResponse resp) {
		try {
			String ids = req.getParameter("checked");
			if (!appUtil.isEmptyOrNull(ids)) {
				String[] idList = ids.split(",");
				for (String id : idList) {
					customerService.deleteById(Long.valueOf(id));
				}
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName() + " > deleteCustomer " + e.getCause());
			return false;
		}
	}

}