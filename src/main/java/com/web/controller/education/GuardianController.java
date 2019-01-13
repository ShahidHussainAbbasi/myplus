package com.web.controller.education;

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
import com.persistence.model.education.Guardian;
import com.service.education.IGuardianService;
import com.web.dto.education.GuardianDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class GuardianController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IGuardianService guardianService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserGuardian", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserGuardian(final HttpServletRequest request) {
		try {
			Guardian filterBy = new Guardian();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Guardian> example = Example.of(filterBy);
			List<Guardian> obj = guardianService.findAll(example);
			if(appUtil.isEmptyOrNull(obj)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),obj);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),obj);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserGuardians", method = RequestMethod.GET)
	@ResponseBody
	public String getUserGuardians(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Guardian filterBy = new Guardian();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Guardian> example = Example.of(filterBy);
			List<Guardian> objs = guardianService.findAll(example);
			sb.append("<option value=''> Nothing Selected </option>");
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getId() +" "+d.getName()+">"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllGuardian", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllGuardian(final HttpServletRequest request) {
		try {
			List<Guardian> objs = guardianService.findAll();
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addGuardian", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addGuardian(@Validated final GuardianDTO dto, final HttpServletRequest request) {
		try {
			Guardian obj = new Guardian();
			User user = requestUtil.getCurrentUser();
			obj .setUserId(user.getId());
			Example<Guardian> example = null;
			if(appUtil.isEmptyOrNull(dto.getId())){
				//create
				obj .setName(dto.getName());
				example = Example.of(obj );
			}else {
				//update
				obj  = modelMapper.map(dto, Guardian.class);
				example = Example.of(obj );
			}
			if(guardianService.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("The Guardian "+dto.getName()+" already exist", null, request.getLocale()));
			}
			obj  = modelMapper.map(dto, Guardian.class);
			obj .setUserId(user.getId());
			obj .setDated(AppUtil.todayDateStr());
			Guardian schoolOwnerTemp = guardianService.save(obj );
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwnerTemp);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwnerTemp);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteGuardian", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteGuardian( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					guardianService.deleteById(Long.valueOf(id));
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
