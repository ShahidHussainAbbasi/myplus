package com.web.controller.education;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.persistence.model.education.Grade;
import com.persistence.model.education.School;
import com.persistence.model.education.Staff;
import com.persistence.model.education.Subject;
import com.service.education.IStaffService;
import com.web.dto.education.StaffDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class StaffController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IStaffService staffService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserStaff", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserStaff(final HttpServletRequest request) {
		try {
			Staff filterBy = new Staff();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Staff> example = Example.of(filterBy);
			List<Staff> obj = staffService.findAll(example);
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
	
	@RequestMapping(value = "/getUserStaffs", method = RequestMethod.GET)
	@ResponseBody
	public String getUserStaffs(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Staff filterBy = new Staff();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Staff> example = Example.of(filterBy);
			List<Staff> objs = staffService.findAll(example);
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllStaff", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStaff(final HttpServletRequest request) {
		try {
			List<Staff> objs = staffService.findAll();
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
	
	@RequestMapping(value = "/addStaff", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addStaff(@Validated final StaffDTO dto, final HttpServletRequest request) {
		try {
			Staff obj = new Staff();
			User user = requestUtil.getCurrentUser();
			obj .setUserId(user.getId());
			Example<Staff> example = null;
			if(appUtil.isEmptyOrNull(dto.getId())){
				//create
				obj .setName(dto.getName());
				example = Example.of(obj );
			}else {
				//update
				obj  = modelMapper.map(dto, Staff.class);
				example = Example.of(obj );
			}
			if(staffService.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("The Staff "+dto.getName()+" already exist", null, request.getLocale()));
			}
			obj  = modelMapper.map(dto, Staff.class);
			Set<School> schools = new HashSet<School>();
			dto.getSchoolIds().forEach(o ->{
				School s = new School();
				s.setId(o);
				schools.add(s);
			});
			obj.setSchools(schools);
			obj.setUserId(user.getId());
			obj.setDated(AppUtil.todayDateStr());
			Staff schoolOwnerTemp = staffService.save(obj );
			StaffDTO dtoTemp  = modelMapper.map(schoolOwnerTemp, StaffDTO.class);
			dtoTemp.setSchoolNames(schoolOwnerTemp.getSchools().stream().map(School::getName).collect(Collectors.toSet()));
			dtoTemp.setGradeNames(schoolOwnerTemp.getGrades().stream().map(Grade::getName).collect(Collectors.toSet()));
			dtoTemp.setSubjectNames(schoolOwnerTemp.getSubjects().stream().map(Subject::getName).collect(Collectors.toSet()));
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()),dtoTemp);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtoTemp);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteStaff", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteStaff( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					staffService.deleteById(Long.valueOf(id));
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
