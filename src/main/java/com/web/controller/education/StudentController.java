package com.web.controller.education;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import com.persistence.model.education.Staff;
import com.persistence.model.education.Student;
import com.service.education.IGradeService;
import com.service.education.IGuardianService;
import com.service.education.ISchoolService;
import com.service.education.IStudentService;
import com.service.education.IVehicleService;
import com.web.dto.education.StudentDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class StudentController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	IGuardianService guardianService;

	@Autowired
	IStudentService studentService;

	@Autowired
	IGradeService gradeService;

	@Autowired
	IVehicleService vehicleService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserStudent", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserStudent(final HttpServletRequest request) {
		try {
			Student filterBy = new Student();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Student> example = Example.of(filterBy);
			List<Student> objs = studentService.findAll(example);
			Set<StudentDTO> dtos = new HashSet<StudentDTO>();
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);

			objs.forEach(obj ->{
				StudentDTO dto = new StudentDTO();
				dto = modelMapper.map(obj, StudentDTO.class);
				dto.setEnrollDate(AppUtil.getDateStr(obj.getEnrollDate()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				dto.setSchoolId(obj.getSchool().getId());
				dto.setSchoolName(obj.getSchool().getBranchName());
				dto.setGuardianId(obj.getGuardian().getId());
				dto.setGuardianName(obj.getGuardian().getName());
				dto.setGradeId(obj.getGrade().getId());
				dto.setGradeName(obj.getGrade().getName());
				dto.setVehicleId(obj.getVehicle().getId());
				dto.setVehicleName(obj.getVehicle().getName());
				
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserStudents", method = RequestMethod.GET)
	@ResponseBody
	public String getUserStudents(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Student filterBy = new Student();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Student> example = Example.of(filterBy);
			List<Student> objs = studentService.findAll(example);
			sb.append("<option value=''> Nothing Selected </option>");
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

	@RequestMapping(value = "/getAllStudent", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStudent(final HttpServletRequest request) {
		try {
			List<Student> objs = studentService.findAll();
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
	
	@RequestMapping(value = "/addStudent", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addStudent(@Validated final StudentDTO dto, final HttpServletRequest request) {
		try {
			User user = requestUtil.getCurrentUser();
			LocalDateTime dated = LocalDateTime.now();
			Student obj = new Student();
			obj.setUserId(user.getId());
			obj.setName(dto.getName());
			Example<Student> example = Example.of(obj);
			if(AppUtil.isEmptyOrNull(dto.getId()) && studentService.exists(example))
				return new GenericResponse("FOUND",messages.getMessage("The Student "+dto.getName()+" already exist", null, request.getLocale()));

			else if(!AppUtil.isEmptyOrNull(dto.getId())) {
				obj = studentService.getOne(dto.getId());
				dated = obj.getEnrollDate();
			}
			obj  = modelMapper.map(dto, Student.class);
			obj.setUserId(user.getId());
			if(AppUtil.isEmptyOrNull(dto.getId()))
				obj.setEnrollDate(dated);
			else
				obj.setEnrollDate(dated);
			obj.setUpdated(dated);

			if(dto.getSchoolId()>0)
				obj.setSchool(schoolService.getOne(dto.getSchoolId()));
			if(dto.getGuardianId()>0)
				obj.setGuardian(guardianService.getOne(dto.getGuardianId()));
			if(dto.getGradeId()>0)
				obj.setGrade(gradeService.getOne(dto.getGradeId()));
			if(dto.getVehicleId()>0)
				obj.setVehicle(vehicleService.getOne(dto.getVehicleId()));
			
			Student schoolOwnerTemp = studentService.save(obj);
			if(AppUtil.isEmptyOrNull(schoolOwnerTemp)) {
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
	
	@RequestMapping(value = "/deleteStudent", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteStudent( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					studentService.updateStatus("Inactive",id);//(Long.valueOf(id));
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
