package com.web.controller.education;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.exception.ConstraintViolationException;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.education.SchoolOwner;
import com.service.education.ISchoolOwnerService;
import com.web.dto.education.SchoolOwnerDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class SchoolOwnerController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ISchoolOwnerService schoolOwnerService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserOwner", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserOwner(final HttpServletRequest request) {
		try {
			SchoolOwner filterBy = new SchoolOwner();
//			User user = (User)(RequestUtil.userProperties.get("user"));
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<SchoolOwner> example = Example.of(filterBy);
			List<SchoolOwner> schoolOwners = schoolOwnerService.findAll(example);
			List<SchoolOwnerDTO> dtos=new ArrayList(); 
			schoolOwners.forEach(obj ->{
				dtos.add(modelMapper.map(obj, SchoolOwnerDTO.class));
			});
			if(appUtil.isEmptyOrNull(dtos)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserOwners", method = RequestMethod.GET)
	@ResponseBody
	public String getUserOwners(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			SchoolOwner filterBy = new SchoolOwner();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<SchoolOwner> example = Example.of(filterBy);
			List<SchoolOwner> schoolOwners = schoolOwnerService.findAll(example);
			schoolOwners.forEach(d -> {
				if(d!=null && d.getId()!=null) {
					sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
				}
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllOwner", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllOwner(final HttpServletRequest request) {
		try {
			List<SchoolOwner> schoolOwners = schoolOwnerService.findAll();
			if(appUtil.isEmptyOrNull(schoolOwners)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwners);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwners);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addOwner", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final SchoolOwnerDTO schoolOwnerDTO, final HttpServletRequest request) {
		try {
			SchoolOwner schoolOwner= new SchoolOwner();
			User user = requestUtil.getCurrentUser();
			schoolOwner.setUserId(user.getId());
			Example<SchoolOwner> example = null;
			if(appUtil.isEmptyOrNull(schoolOwnerDTO.getId())){
				//create
				schoolOwner.setName(schoolOwnerDTO.getName());
				example = Example.of(schoolOwner);
			}else {
				//update
				schoolOwner = modelMapper.map(schoolOwnerDTO, SchoolOwner.class);
				example = Example.of(schoolOwner);
			}
			if(schoolOwnerService.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("The Owner "+schoolOwnerDTO.getName()+" already exist", null, request.getLocale()));
			}
			schoolOwner = modelMapper.map(schoolOwnerDTO, SchoolOwner.class);
			schoolOwner.setUserId(user.getId());
			schoolOwner.setDated(AppUtil.todayDateStr());
			SchoolOwner schoolOwnerTemp = schoolOwnerService.save(schoolOwner);
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwnerTemp);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwnerTemp);
			}
		} catch (DataIntegrityViolationException e) {
			e.printStackTrace();
			return new GenericResponse("FOUND",messages.getMessage("The Owner "+schoolOwnerDTO.getName()+" already exist", null, request.getLocale()));
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteOwner", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteOwner( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					schoolOwnerService.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
