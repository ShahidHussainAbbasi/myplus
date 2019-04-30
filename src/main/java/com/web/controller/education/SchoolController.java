package com.web.controller.education;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
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
import com.persistence.model.education.Owner;
import com.persistence.model.education.School;
import com.service.education.IOwnerService;
import com.service.education.ISchoolService;
import com.web.dto.education.SchoolDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class SchoolController {

	@Autowired
	private MessageSource messages;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	IOwnerService ownerService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	List<School> objs=null;
	List<SchoolDTO> dtos = null;
	
	@RequestMapping(value = "/getMainBranchName", method = RequestMethod.GET)
	@ResponseBody
	public String getMainBranchName() {
		try {
			School filterBy = new School();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<School> example = Example.of(filterBy);
			return schoolService.findAll(example).get(0).getName();
		} catch (Exception e) {
			log.error(this.getClass().getName()+"  >>>  "+e.getClass());
			return "";
		}
	}
	
	@RequestMapping(value = "/getUserSchool", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserSchool(final HttpServletRequest request) {
		try {
			School filterBy = new School();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<School> example = Example.of(filterBy);
	        objs = schoolService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			
			dtos = new ArrayList<SchoolDTO>();
			objs.forEach(obj ->{
				SchoolDTO dto = modelMapper.map(obj,SchoolDTO.class);
				dto.setOwnerIds(obj.getOwners().stream().map(Owner::getId).collect(Collectors.toSet()));
				dto.setOwnerNames(obj.getOwners().stream().map(Owner::getName).collect(Collectors.toSet()));
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/getUserSchools", method = RequestMethod.GET)
	@ResponseBody
	public String getUserSchools(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			School filterBy = new School();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<School> example = Example.of(filterBy);
			List<School> schools = schoolService.findAll(example);
			if(!appUtil.isEmptyOrNull(schools) && schools.size()>1)
				sb.append("<option value=''>Nothing Selected</option>");
			
			schools.forEach(d -> {
				if(d!=null && d.getId()!=null) {
					sb.append("<option value="+d.getId()+">"+d.getBranchName()+"</option>");
				}
			});
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllSchool", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSchool(final HttpServletRequest request) {
		try {
			List<School> schoolOwners = schoolService.findAll();
			if(appUtil.isEmptyOrNull(schoolOwners)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwners);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),schoolOwners);
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addSchool", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addSchool(@Validated final SchoolDTO dto, final HttpServletRequest request) {
		try {
			School obj= new School();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())) {
				obj.setUserId(user.getId());
				obj.setBranchName(dto.getBranchName());
				Example<School> example = Example.of(obj);
				if(schoolService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The School "+dto.getName()+" already exist", null, request.getLocale()));
			}

			obj = modelMapper.map(dto, School.class);
			obj.setDated(dated);
			obj.setUpdated(dated);

			Set<Owner> owners = new HashSet<>();
			for(Long id:dto.getOwnerIds()) {
				if(!appUtil.isEmptyOrNull(id))
					owners.add(ownerService.getOne(id));
			}
			obj.setOwners(owners);//ssetOwnerIds(schoolDTO.getOwnerIds());
			School schoolOwnerTemp = schoolService.save(obj);
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteSchool", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteSchool( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
//					schoolService.deleteById(Long.valueOf(id));
					schoolService.deleteById(Long.valueOf(id));//.updateStatus("Inactive", id);
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
