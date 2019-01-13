package com.web.controller.education;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import com.persistence.model.education.School;
import com.persistence.model.education.SchoolOwner;
import com.service.education.ISchoolService;
import com.web.dto.education.SchoolDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class SchoolController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	List<School> entities=null;
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
	        entities = schoolService.findAll(example);
			if(appUtil.isEmptyOrNull(entities)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),entities);
			}else {
				SchoolDTO dto=null;
				dtos = new ArrayList<SchoolDTO>();
				for(School s:entities) {
					dto = new SchoolDTO();
//					dto = modelMapper.map(s,SchoolDTO.class);
					Set<Long> ids= new HashSet<>();
					Set<String> names= new HashSet<>();
					s.getOwners().forEach(e -> 
						{
							ids.add(e.getId());
							names.add(e.getName());
						}
					);
					
					dto = modelMapper.map(s,SchoolDTO.class);
					dto.setOwnerIds(ids);
					dto.setOwnerNames(names);
//					dto.setOwnerIds(s.getOwners().stream().map(SchoolOwner::getId).collect(Collectors.toSet()));
//					dto.setOwnerNames(s.getOwners().stream().map(SchoolOwner::getName).collect(Collectors.toSet()));
					dtos.add(dto);
				}
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
			}
		} catch (Exception e) {
			e.printStackTrace();
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
			schools.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
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
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addSchool", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addSchool(@Validated final SchoolDTO schoolDTO, final HttpServletRequest request) {
		try {
			School school= new School();
			User user = requestUtil.getCurrentUser();
			school.setUserId(user.getId());
			Example<School> example = null;
			if(appUtil.isEmptyOrNull(schoolDTO.getId())){
				//create
				school.setName(schoolDTO.getName());
				example = Example.of(school);
			}else {
				//update
				school = modelMapper.map(schoolDTO, School.class);
				Set<SchoolOwner> owners = new HashSet<>();
				SchoolOwner owner = null;
				for(Long id:schoolDTO.getOwnerIds()) {
					owner = new SchoolOwner();
					owner.setId(id);
					owners.add(owner);
				}
				school.setOwners(owners);//ssetOwnerIds(schoolDTO.getOwnerIds());
				example = Example.of(school);
			}
			if(schoolService.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("The School "+schoolDTO.getName()+" already exist", null, request.getLocale()));
			}
			school = modelMapper.map(schoolDTO, School.class);
			Set<SchoolOwner> owners = new HashSet<>();
			SchoolOwner owner = null;
			for(Long id:schoolDTO.getOwnerIds()) {
				owner = new SchoolOwner();
				owner.setId(id);
				owners.add(owner);
			}
			school.setOwners(owners);//ssetOwnerIds(schoolDTO.getOwnerIds());
//			school.setOwnerNames(schoolDTO.getOwnerNames());
			school.setUserId(user.getId());
			school.setDated(AppUtil.todayDateStr());
			School schoolOwnerTemp = schoolService.save(school);
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
	
	@RequestMapping(value = "/deleteSchool", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteSchool( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					schoolService.deleteById(Long.valueOf(id));
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
