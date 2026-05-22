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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.business.Customer;
import com.service.business.ICustomerService;
import com.web.dto.business.CustomerDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

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
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Customer> example = Example.of(filterBy);
			List<Customer> objs = customerService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<CustomerDTO> dtos=new ArrayList<CustomerDTO>(); 
			objs.forEach(obj ->{
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
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
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
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			
			Example<Customer> example = Example.of(obj);
			if(appUtil.isEmptyOrNull(dto.getCustomerId())){
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				if(customerService.exists(example)) {
					return new GenericResponse("FOUND",messages.getMessage("The Customer "+dto.getName()+" already exist", null, request.getLocale()));		
				}		
			}
			
			obj = modelMapper.map(dto, Customer.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getCustomerId())) {
				obj.setDated(customerService.getReferenceById(dto.getCustomerId()).getDated());
//				dated = obj.getDated();
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);
			obj.setUserType(user.getUserType());
			obj = customerService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addCustomer "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteCustomer", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteCustomer( HttpServletRequest req, HttpServletResponse resp ) {
		try {
		String ids = req.getParameter("checked");
			if(!appUtil.isEmptyOrNull(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					customerService.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > deleteCustomer "+e.getCause());			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
