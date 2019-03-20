package com.web.controller.education;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.education.Discount;
import com.persistence.model.education.Grade;
import com.persistence.model.education.Guardian;
import com.persistence.model.education.School;
import com.persistence.model.education.Student;
import com.persistence.model.education.Vehicle;
import com.service.education.IDiscountService;
import com.service.education.IGradeService;
import com.service.education.IGuardianService;
import com.service.education.ISchoolService;
import com.service.education.IStudentService;
import com.service.education.IVehicleService;
import com.web.dto.education.StudentDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class StudentController {

//	@Autowired
//	private MessageSource messages;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	IGuardianService guardianService;
	
	@Autowired
	IDiscountService discountService;

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
				return new GenericResponse("NOT_FOUND");

			objs.forEach(obj ->{
				StudentDTO dto = new StudentDTO();
				dto = modelMapper.map(obj, StudentDTO.class);
				dto.setEnrollDate(AppUtil.getLocalDateStr(obj.getEnrollDate()));
				dto.setDateOfBirth(AppUtil.getLocalDateStr(obj.getDateOfBirth()));
				dto.setYs(AppUtil.getLocalDateStr(obj.getYs()));
				dto.setYe(AppUtil.getLocalDateStr(obj.getYe()));
				dto.setUpdatedStr(AppUtil.getDateStr(obj.getUpdated()));
				if(!AppUtil.isEmptyOrNull(obj.getSchoolId())) {
					Optional<School> school = schoolService.findById(obj.getSchoolId());
					if(school.isPresent()) {
						dto.setSchoolId(school.get().getId());
						dto.setSchoolName(school.get().getBranchName());
					}
				}
				if(!AppUtil.isEmptyOrNull(obj.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(obj.getGradeId());
					if(grade.isPresent()) {
						dto.setGradeId(grade.get().getId());
						dto.setGradeName(grade.get().getName());
					}
				}
				if(!AppUtil.isEmptyOrNull(obj.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(obj.getGuardianId());
					if(g.isPresent()) {
						dto.setGuardianId(g.get().getId());
						dto.setGuardianName(g.get().getName());
					}
				}
				if(!AppUtil.isEmptyOrNull(obj.getDiscountId())) {
					Optional<Discount> d = discountService.findById(obj.getDiscountId());
					if(d.isPresent()) {
						dto.setDiscountId(d.get().getId());
						dto.setDiscountName(d.get().getName());
						
						if(AppUtil.isEmptyOrNull(obj.getNd()))
							dto.setNd(d.get().getAmount());
							if(AppUtil.isEmptyOrNull(obj.getDi()))
								dto.setDi(d.get().getDi());
						
						
					}
				}
				if(!AppUtil.isEmptyOrNull(obj.getVehicleId())) {
					Optional<Vehicle> vehicle = vehicleService.findById(obj.getVehicleId());
					if(vehicle.isPresent()) {
						dto.setVehicleId(vehicle.get().getId());
						dto.setVehicleName(vehicle.get().getName());
					}
				}				
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS","",dtos);
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" > "+e.getCause());
			return new GenericResponse("ERROR",e.getCause().toString());
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
			log.error(this.getClass().getName() +" > "+e.getCause());
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllStudent", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStudent(final HttpServletRequest request) {
		try {
			List<Student> objs = studentService.findAll();
			if(AppUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND");
			}else {
				return new GenericResponse("SUCCESS",objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" > "+e.getCause());
			return new GenericResponse("ERROR",e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addStudent", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addStudent(@Validated final StudentDTO dto, final HttpServletRequest request) {
		try {
			Student obj = new Student();
			User user = requestUtil.getCurrentUser();
			LocalDateTime dated = LocalDateTime.now();
			dto.setUserId(user.getId());
			obj.setUserId(user.getId());
//			obj.setUserId(user.getId());
			if(AppUtil.isEmptyOrNull(dto.getId())){
				obj.setEnrollNo(dto.getEnrollNo());
				obj.setGuardianId(dto.getGuardianId());
				Example<Student> example = Example.of(obj);
				if(studentService.exists(example))
					return new GenericResponse("FOUND");
			}
			obj  = modelMapper.map(dto, Student.class);
			obj.setEnrollDate(AppUtil.getLocalDate(dto.getEnrollDate()));
			obj.setDateOfBirth(AppUtil.getLocalDate(dto.getDateOfBirth()));
			obj.setYs(AppUtil.getLocalDate(dto.getYs()));
			obj.setYe(AppUtil.getLocalDate(dto.getYe()));
			obj.setDated(dated);			
			obj.setUpdated(dated);

			if(AppUtil.isEmptyOrNull(obj.getEmail()) || AppUtil.isEmptyOrNull(obj.getMobile()) || AppUtil.isEmptyOrNull(obj.getAddress())) {
				Optional<Guardian> g = guardianService.findById(obj.getGuardianId());
				if(g.isPresent()) {
					if(AppUtil.isEmptyOrNull(obj.getEmail()))
						obj.setEmail(g.get().getEmail());
					if(AppUtil.isEmptyOrNull(obj.getMobile()))
						obj.setMobile(g.get().getMobile());
					if(AppUtil.isEmptyOrNull(obj.getAddress()))
						obj.setAddress(g.get().getPermAddress());
				}
			}
//			obj.setSchoolId(dto.getSchoolId());
//			obj.setGradeId(dto.getGradeId());
//			if(dto.getGuardianId()>0)
//				obj.setGuardianId(dto.getGuardianId());
//
//			obj.setVehicleId(dto.getVehicleId());
			
			Student schoolOwnerTemp = studentService.save(obj);
			if(AppUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED");
			}else {
				return new GenericResponse("SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" > "+e.getCause());
			return new GenericResponse("ERROR",e.getCause().toString());
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
					studentService.deleteById(Long.valueOf(id));//.updateStatus("Inactive",id);//(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" > "+e.getCause());
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
