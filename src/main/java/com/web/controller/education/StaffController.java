package com.web.controller.education;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
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
import com.service.education.IGradeService;
import com.service.education.ISchoolService;
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
	ISchoolService schoolService;

	@Autowired
	IGradeService gradeService;

//	@Autowired
//	IStaffService staffService;

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
			List<Staff> objs = staffService.findAll(example);			
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			
			List<StaffDTO> dtos= new ArrayList<>();
			objs.forEach(obj ->{
				StaffDTO dto = modelMapper.map(obj, StaffDTO.class);
				dto.setSchoolNames(obj.getSchools()==null?null:obj.getSchools().stream().map(School::getBranchName).collect(Collectors.toSet()));
				dto.setGradeNames(obj.getGrades()==null?null:obj.getGrades().stream().map(Grade::getName).collect(Collectors.toSet()));
				dto.setTimeInStr(obj.getTimeIn().toString());
				dto.setTimeOutStr(obj.getTimeOut().toString());
				dto.setDatedStr(AppUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
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
			if(AppUtil.isEmptyOrNull(objs)){
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
			LocalDateTime dated = LocalDateTime.now();
			obj .setUserId(user.getId());
			obj .setName(dto.getName());
			Example<Staff> example = Example.of(obj );
			if(AppUtil.isEmptyOrNull(dto.getId()) &&staffService.exists(example))
				return new GenericResponse("FOUND",messages.getMessage("The Staff "+dto.getName()+" already exist", null, request.getLocale()));
				
			else if(!AppUtil.isEmptyOrNull(dto.getId())) {
				obj = staffService.getOne(dto.getId());
				dated = obj.getDated();
			}
			obj  = modelMapper.map(dto, Staff.class);
			obj.setUserId(user.getId());
			if(AppUtil.isEmptyOrNull(dto.getId()))
				obj.setDated(dated);
			else
				obj.setDated(dated);
			obj.setUpdated(dated);

			Set<School> schools = new HashSet<School>();
			dto.getSchoolIds().forEach(id ->{
				School school = schoolService.getOne(id);
				schools.add(school);//schoolService.findById(o).get());
			});			
			Set<Grade> grades = new HashSet<Grade>();
			dto.getGradeIds().forEach(id ->{
				Grade grade = gradeService.getOne(id);
				grades.add(grade);//gradeService.findById(o).get());
			});
			obj.setSchools(schools);
			obj.setGrades(grades);
			obj.setTimeIn(LocalTime.parse(dto.getTimeInStr()));
			obj.setTimeOut(LocalTime.parse(dto.getTimeOutStr()));

			obj = staffService.save(obj);
			if(AppUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
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
//					staffService.deleteById(Long.valueOf(id));
					staffService.updateStatus("Inactive", id);
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
