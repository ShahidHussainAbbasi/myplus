package com.web.controller.education;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import com.persistence.model.education.Grade;
import com.persistence.model.education.School;
import com.service.education.IGradeService;
import com.service.education.ISchoolService;
import com.service.education.ISubjectService;
import com.web.dto.education.GradeDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class GradeController {

	@Autowired
	private MessageSource messages;

	@Autowired
	IGradeService gradeService;

	@Autowired
	ISubjectService subjectService;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserGrade", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserGrade(final HttpServletRequest request) {
		try {
			Grade filterBy = new Grade();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Grade> example = Example.of(filterBy);
			List<Grade> objs = gradeService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<GradeDTO> dtos = new ArrayList<>();
			objs.forEach(obj->{
				GradeDTO dto = new GradeDTO();
				dto = modelMapper.map(obj, GradeDTO.class);
				if(!appUtil.isEmptyOrNull(obj.getSchoolId())) {
					Optional<School> school = schoolService.findById(dto.getSchoolId());
					if(school.isPresent()) {
						dto.setSchoolId(school.get().getId());
						dto.setSchoolName(school.get().getBranchName());
					}
				}else {
					dto.setSchoolName("");
				}
				if(!appUtil.isEmptyOrNull(dto.getTimeFrom()))
					dto.setTimeFromStr(obj.getTimeFrom().toString());
				if(!appUtil.isEmptyOrNull(dto.getTimeTo()))
					dto.setTimeToStr(obj.getTimeTo().toString());
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserGrades", method = RequestMethod.GET)
	@ResponseBody
	public String getUserGrades(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Grade filterBy = new Grade();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Grade> example = Example.of(filterBy);
			List<Grade> grades = gradeService.findAll(example);
			sb.append("<option value=''>Nothing Selected</option>");
			grades.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getId()+">"+d.getName()+" ~ "+d.getId()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllGrade", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllGrade(final HttpServletRequest request) {
		try {
			List<Grade> grades = gradeService.findAll();
			if(appUtil.isEmptyOrNull(grades)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),grades);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),grades);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addGrade", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addGrade(@Validated final GradeDTO dto, final HttpServletRequest request) {
		try {
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			Grade obj = new Grade();
			dto.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())) {
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				obj.setSchoolId(dto.getSchoolId());
				Example<Grade> example = Example.of(obj);
				if(gradeService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Grade "+dto.getName()+" already exist", null, request.getLocale()));
			}

			obj  = modelMapper.map(dto, Grade.class);
			obj.setDated(dated);
			obj.setUpdated(dated);
			if(!appUtil.isEmptyOrNull(dto.getTimeFromStr()))
				obj.setTimeFrom(LocalTime.parse(dto.getTimeFromStr()));
			if(!appUtil.isEmptyOrNull(dto.getTimeToStr()))
				obj.setTimeTo(LocalTime.parse(dto.getTimeToStr()));
//			School school = schoolService.getOne(dto.getSchoolId()); 
//			obj.setSchoolId(dto.getSchoolId());
			
			Grade schoolOwnerTemp = gradeService.save(obj);
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()),dto);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dto);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteGrade", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteGrade( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
//					gradeService.deleteById(Long.valueOf(id));
					gradeService.deleteById(Long.valueOf(id));//.updateStatus("Inactive", id);
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
