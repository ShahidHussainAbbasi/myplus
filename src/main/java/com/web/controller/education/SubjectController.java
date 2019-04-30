package com.web.controller.education;

import java.time.LocalDateTime;
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
import com.persistence.model.education.Subject;
import com.service.education.IGradeService;
import com.service.education.ISchoolService;
import com.service.education.ISubjectService;
import com.web.dto.education.SubjectDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class SubjectController {

	@Autowired
	private MessageSource messages;

	@Autowired
	ISubjectService subjectService;

	@Autowired
	IGradeService gradeService;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserSubject", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserSubject(final HttpServletRequest request) {
		try {
			Subject filterBy = new Subject();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Subject> example = Example.of(filterBy);
			List<Subject> objs = subjectService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);

			List<SubjectDTO>  dtos = new ArrayList<>();
			objs.forEach(obj ->{
				SubjectDTO  dto = new SubjectDTO();
				dto = modelMapper.map(obj, SubjectDTO.class);
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				if(!appUtil.isEmptyOrNull(obj.getGrade()))
					dto.setGradeName(obj.getGrade().getName());
				dtos.add(dto);
			});			
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserSubjects", method = RequestMethod.GET)
	@ResponseBody
	public String getUserSubjects(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Subject filterBy = new Subject();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Subject> example = Example.of(filterBy);
			List<Subject> objs = subjectService.findAll(example);
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

	@RequestMapping(value = "/getAllSubject", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllSubject(final HttpServletRequest request) {
		try {
			List<Subject> objs = subjectService.findAll();
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
	
	@RequestMapping(value = "/addSubject", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addSubject(@Validated final SubjectDTO dto, final HttpServletRequest request) {
		try {
			Subject obj  = modelMapper.map(dto, Subject.class);
			User user = requestUtil.getCurrentUser();
			LocalDateTime dated = LocalDateTime.now();
			obj .setUserId(user.getId());
			obj.setName(dto.getName());
			Example<Subject> example = Example.of(obj);
			if(appUtil.isEmptyOrNull(dto.getId()) && subjectService.exists(example))
				return new GenericResponse("FOUND",messages.getMessage("The Subject "+dto.getName()+" already exist", null, request.getLocale()));

			obj  = modelMapper.map(dto, Subject.class);
			obj.setUserId(user.getId());
			obj.setDated(dated);
			obj.setUpdated(dated);

			Optional<Grade> g = gradeService.findById(dto.getGradeId());
			if(g.isPresent())
				obj.setGrade(g.get());
			Subject objTemp = subjectService.save(obj);
			if(appUtil.isEmptyOrNull(objTemp)){
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
	
	@RequestMapping(value = "/deleteSubject", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteSubject( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
//					subjectService.deleteById(Long.valueOf(id));
					subjectService.deleteById(Long.valueOf(id));//.updateStatus("Inactive", id);
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
