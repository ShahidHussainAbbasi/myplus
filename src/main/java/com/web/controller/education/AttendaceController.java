package com.web.controller.education;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import com.persistence.model.education.Attendance;
import com.persistence.model.education.Grade;
import com.persistence.model.education.Guardian;
import com.persistence.model.education.Student;
import com.service.education.IAttendanceService;
import com.service.education.IDiscountService;
import com.service.education.IGradeService;
import com.service.education.IGuardianService;
import com.service.education.ISchoolService;
import com.service.education.IStudentService;
import com.service.education.IVehicleService;
import com.web.dto.education.AttendanceDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

import lombok.extern.slf4j.Slf4j;

@Controller
@Slf4j
public class AttendaceController {

	@Autowired
	private MessageSource messages;

	@Autowired
	IGuardianService guardianService;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	IAttendanceService service;

	@Autowired
	IStudentService studentService;

	@Autowired
	IGradeService gradeService;

	@Autowired
	IDiscountService discountService;

	@Autowired
	IVehicleService vehicleService;

	@Autowired
	RequestUtil requestUtil;

	@Autowired
	AppUtil appUtil;
	
	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserStudentMap", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserStudentMap(final HttpServletRequest request) {
		try {
//			request.isUserInRole(role)
			Student filterBy = new Student();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Student> example = Example.of(filterBy);
			List<Student> objs = studentService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND");

			Map<String,AttendanceDTO> sm = new HashMap<String, AttendanceDTO>();
			
			objs.forEach(obj ->{
				AttendanceDTO dto = new AttendanceDTO();
				if(!appUtil.isEmptyOrNull(obj.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(obj.getGradeId());
					if(grade.isPresent()) {
//						dto.setGradeId(grade.get().getId());
						dto.setG(grade.get().getName());
					}
				}
				if(!appUtil.isEmptyOrNull(obj.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(obj.getGuardianId());
					if(g.isPresent()) {
//						dto.setGuardianId(g.get().getId());
						dto.setGn(g.get().getName());
					}
				}
				dto.setEn(obj.getEnrollNo());
				dto.setSn(obj.getName());
				dto.setUserId(user.getId());
				sm.put(obj.getEnrollNo(),dto);
			});
			return new GenericResponse("SUCCESS","",sm);
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" > "+e.getCause());
			return new GenericResponse("ERROR",e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserA", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserA(final HttpServletRequest request) {
		try {
			Attendance filterBy = new Attendance();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Attendance> example = Example.of(filterBy);
			List<Attendance> objs = service.findAll(example);
			Set<AttendanceDTO> dtos = new HashSet<AttendanceDTO>();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);

			objs.forEach(obj ->{
				AttendanceDTO dto = new AttendanceDTO();
				dto = modelMapper.map(obj, AttendanceDTO.class);
				dto.setDtStr(appUtil.getLocalDateTimeStr(obj.getDt()));
				
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" >>> "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
//	@RequestMapping(value = "/findA", method = RequestMethod.GET)
//	@ResponseBody
//	public GenericResponse findA(final HttpServletRequest request) {
//		try {
//			String en = request.getParameter("input");
//			Student filter = new Student();
//			User user = requestUtil.getCurrentUser();
//			filter.setUserId(user.getId());
//			filter.setEnrollNo(en);
//	        Example<Student> example = Example.of(filter);
//	        //getting student detail
//			Optional<Student> obj = studentService.findOne(example);
//			if(!obj.isPresent())
//				return new GenericResponse("NOT_FOUND");
//			
//			Student s = obj.get();
//			AttendanceDTO dto = new AttendanceDTO();//modelMapper.map(obj, FeeCollectionDTO.class);
//			if(!AppUtil.isEmptyOrNull(s.getSchoolId()))
//				dto.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
//			
//			if(!AppUtil.isEmptyOrNull(s.getGuardianId())) {
//				Optional<Guardian> g = guardianService.findById(s.getGuardianId());
//				if(g.isPresent())
//					dto.setGn(g.get().getName());
//			}
//			dto.setSn(s.getName());
//			if(!AppUtil.isEmptyOrNull(s.getGradeId())) {
//				Optional<Grade> grade = gradeService.findById(s.getGradeId());
//				if(grade.isPresent())
//					dto.setG(grade.get().getName());
//			}
//			dto.setVf(s.getVf()==null?0:s.getVf());
//			if(!AppUtil.isEmptyOrNull(s.getDiscountId())) {
//				Optional<Discount> d = discountService.findById(s.getDiscountId());
//				if(d.isPresent()) {
//					dto.setDt(d.get().getType());
//					dto.setD(d.get().getAmount());
//				}
//			}
//			
//			Attendance f = new Attendance();
//			f.setUserId(user.getId());
//			f.setEn(en);
//	        Example<Attendance> e = Example.of(f);
//			List<Attendance> l = service.findAll(e,AppUtil.getPageRequest(0,1,AppUtil.orderByDESC("pd"))).getContent();
//			if(!AppUtil.isEmptyOrNull(l)) {
//				dto.setLpd(l.get(0).getPd());
//				dto.setDb(l.get(0).getDb());
//			}
//			
//			dto.setF(s.getFee());
//			dto.setDd(s.getDueDay());
//			dto.setPdStr(AppUtil.getLocalDateStr());
//			return new GenericResponse("SUCCESS",dto);
//		} catch (Exception e) {
//			e.printStackTrace();
//			log.error(this.getClass().getName() +" >>> "+e.getCause());
//			return new GenericResponse("ERROR",messages.getMessage(e.getCause()+"", null, request.getLocale()));
//		}
//	}

	@RequestMapping(value = "/getAllA", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStudent(final HttpServletRequest request) {
		try {
			List<Attendance> objs = service.findAll();
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" >>> "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/markAttendance2", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse markAttendance2(final AttendanceDTO data, final HttpServletRequest request) {
		try {
			Attendance obj = new Attendance();
			obj = modelMapper.map(data, Attendance.class);
//			Student filter = new Student();
//			User user = requestUtil.getCurrentUser();
//			filter.setUserId(user.getId());
//			filter.setEnrollNo(dto.getEn());
//	        Example<Student> example = Example.of(filter);
//	        //getting student detail
//			Optional<Student> o = studentService.findOne(example);
//			if(!o.isPresent())
//				return new GenericResponse("NOT_FOUND");
			
//			Student s = new Student();
			
//			obj.setUserId(user.getId());
			obj.setDt(LocalDateTime.now());
//			obj.setI(LocalTime.now());
//			obj.setO(LocalTime.now());			
//			obj.setEn(dto.getEn());
//			obj.setSn(s.getName());
			
//			Optional<Grade> g = gradeService.findById(s.getGradeId());
//			if(!g.isPresent())
//				return new GenericResponse("NOT_FOUND");
			
//			obj.setGn(g.get().getName());
			
			obj =service.save(obj);
			if(appUtil.isEmptyOrNull(obj))
				return new GenericResponse("FAILED");

			return new GenericResponse("SUCCESS",obj);
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" >>> "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/markAttendance", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse markAttendance(@Validated final AttendanceDTO dto, final HttpServletRequest request) {
		try {
			Attendance obj = new Attendance();
			Student filter = new Student();
			User user = requestUtil.getCurrentUser();
			filter.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getEn()))
				return new GenericResponse("Null/Empty Value","Enrollment must not be null or empty");
			
			filter.setEnrollNo(dto.getEn());
	        Example<Student> example = Example.of(filter);
	        //getting student detail
			Optional<Student> o = studentService.findOne(example);
			if(!o.isPresent())
				return new GenericResponse("NOT_FOUND","Student not found");
			
			Student s = o.get();
			
			obj.setUserId(user.getId());
			obj.setDt(LocalDateTime.now());
			obj.setIn(LocalTime.now());
			obj.setOut(LocalTime.now());			
			obj.setEn(dto.getEn());
			obj.setSn(s.getName());
			
			Optional<Grade> g = gradeService.findById(s.getGradeId());
			if(!g.isPresent())
				return new GenericResponse("NOT_FOUND","Student is not registered with Grade");
			
			obj.setGn(g.get().getName());
			obj.setGrid(g.get().getId());
			
			obj =service.save(obj);
			if(appUtil.isEmptyOrNull(obj))
				return new GenericResponse("FAILED");

			return new GenericResponse("SUCCESS");
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" >>> "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}

	@RequestMapping(value = "/deleteA", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteA( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					service.deleteById(Long.valueOf(id));//.updateStatus("Inactive",id);//(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			log.error(this.getClass().getName() +" >>> "+e.getCause());
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
