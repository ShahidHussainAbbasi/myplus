package com.web.controller.education;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.education.Discount;
import com.persistence.model.education.FeeCollection;
import com.persistence.model.education.Grade;
import com.persistence.model.education.Guardian;
import com.persistence.model.education.Student;
import com.service.education.IDiscountService;
import com.service.education.IFeeCollectionService;
import com.service.education.IGradeService;
import com.service.education.IGuardianService;
import com.service.education.ISchoolService;
import com.service.education.IStudentService;
import com.service.education.IVehicleService;
import com.web.dto.education.FeeCollectionDTO;
import com.web.dto.education.FeeVoucherDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

import lombok.extern.slf4j.Slf4j;

@Controller
@Slf4j
public class FeeCollectionController {

	@Autowired
	private MessageSource messages;

	@Autowired
	IGuardianService guardianService;

	@Autowired
	ISchoolService schoolService;

	@Autowired
	IFeeCollectionService feeCollectionService;

	@Autowired
	IStudentService studentService;

	@Autowired
	IGradeService gradeService;

	@Autowired
	IDiscountService discountService;

	@Autowired
	IVehicleService vehicleService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	private final Short ENROLL_NO = 0;
	private final Short GUARDIAN = 1;
	private final Short GRADE = 2;
	private final Short CAMPUS = 3;
	private final Short CURRENT_MONTH = 0;
	private final String STUDENTS = "Students"; 
	private final String GUARDIANS = "Guardians"; 
	private final String GRADES = "Grades"; 
	private final String SCHOOLS = "Schools"; 
	
	@RequestMapping(value = "/getUserFc", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserFc(final HttpServletRequest request) {
		try {
			FeeCollection filterBy = new FeeCollection();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<FeeCollection> example = Example.of(filterBy);
			List<FeeCollection> objs = feeCollectionService.findAll(example);
			Set<FeeCollectionDTO> dtos = new HashSet<FeeCollectionDTO>();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);

			objs.forEach(obj ->{
				FeeCollectionDTO dto = new FeeCollectionDTO();
				dto = modelMapper.map(obj, FeeCollectionDTO.class);
				dto.setDd(obj.getDd());
				dto.setPdStr(appUtil.getLocalDateStr(obj.getPd()));
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
	
	@SuppressWarnings("null")
	@RequestMapping(value = "/findFc", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse findFc(final HttpServletRequest request) {
		try {
			FeeCollectionDTO dto = new FeeCollectionDTO();//modelMapper.map(obj, FeeCollectionDTO.class);
//			List<FeeCollection> dtol = new ArrayList<FeeCollection>();
			Map<String, Object> fcm = new HashMap<String, Object>();
			String en = request.getParameter("input");
			Student filter = new Student();
			User user = requestUtil.getCurrentUser();
			filter.setUserId(user.getId());
			filter.setEnrollNo(en);
	        Example<Student> example = Example.of(filter);
	        //getting student detail
			Optional<Student> obj = studentService.findOne(example);
			if(!obj.isPresent())
				return new GenericResponse("NOT_FOUND","Student not found");
			
			Student s = obj.get();
			if(!appUtil.isEmptyOrNull(s.getSchoolId()))
				dto.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
			
			if(!appUtil.isEmptyOrNull(s.getGuardianId())) {
				Optional<Guardian> g = guardianService.findById(s.getGuardianId());
				if(g.isPresent())
					dto.setGn(g.get().getName());
			}
			dto.setSn(s.getName());
			if(!appUtil.isEmptyOrNull(s.getGradeId())) {
				Optional<Grade> grade = gradeService.findById(s.getGradeId());
				if(grade.isPresent())
					dto.setG(grade.get().getName());
			}
			dto.setVf(s.getVf()==null?0:s.getVf());
			dto.setF(s.getFee());
			dto.setDd(s.getDueDay());
			if(!appUtil.isEmptyOrNull(s.getDiscountId())) {
				Optional<Discount> d = discountService.findById(s.getDiscountId());
				if(d.isPresent()) {
					dto.setDt(d.get().getDi());
					dto.setD(d.get().getAmount());
				}else {
					dto.setDt(obj.get().getDi());
					dto.setD(obj.get().getNd());
				}
			}else {
				dto.setDt(obj.get().getDi());
				dto.setD(obj.get().getNd());
			}
			
			FeeCollection f = new FeeCollection();
			f.setUserId(user.getId());
			f.setEn(en);
	        Example<FeeCollection> e = Example.of(f);
			List<FeeCollection> L = feeCollectionService.findAll(e,appUtil.getPageRequest(appUtil.orderByDESC("pd"))).getContent();
			if(!appUtil.isEmptyOrNull(L)) {
				dto.setLpd(L.get(0).getPd());
				dto.setDb(L.get(0).getDb());
			}
			
			dto.setPdStr(appUtil.getLocalDateStr());
			fcm.put("sf", dto);
			//rest of detail
			
			//update list with last payment date where balance is due mean >0 
			//Note: need to implement DSL or some criteria
			if(!appUtil.isEmptyOrNull(L)) {
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				for (FeeCollection o : L) {
					if(o.getDb()>0)
						L2.add(o);
					else {
						L2.add(o);//last payment
						break;
					}
				}
				fcm.put("sfd", L2);
			}
			return new GenericResponse("SUCCESS",fcm);
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",messages.getMessage(e.getCause()+"", null, request.getLocale()));
		}
	}

	@RequestMapping(value = "/loadFV", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse loadFV(final FeeVoucherDTO dto) {
		try {
			@SuppressWarnings({ "unchecked", "rawtypes" })
			List<GenericResponse> ML = new ArrayList();
			List<Long> SIDs = new ArrayList<Long>();
			List<Long> ids = new ArrayList<Long>();
			User user = requestUtil.getCurrentUser();
			Iterable<String> list = Stream.of(dto.getVi().split(",")).collect(Collectors.toList());
			list.forEach(id ->{	
				ids.add(Long.valueOf(id));
			});
			if(dto.getVb().equals(STUDENTS)) {
				if(dto.getInclExclSelected().equals("Default All")) {
					SIDs.addAll(studentService.findStudentsByUserId(user.getId(),appUtil.ACTIVE).stream().map(Student::getId).collect(Collectors.toSet()));
				}else if(dto.getInclExclSelected().equals("include")) {
					SIDs.addAll(studentService.findStudentsByStudentIdsAndUserId(user.getId(), ids,appUtil.ACTIVE).stream().map(Student::getId).collect(Collectors.toSet()));
				}else {
					SIDs.addAll(studentService.findStudentsByUserIdAndNotStudentIds(user.getId(), ids,appUtil.ACTIVE).stream().map(Student::getId).collect(Collectors.toSet()));
				}
			}else if(dto.getVb().equals(GUARDIANS)) {
				SIDs.addAll(studentService.findStudentsByGuardianIdsAndUserId(user.getId(), ids,appUtil.ACTIVE).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getVb().equals(GRADES)) {
				SIDs.addAll(studentService.findStudentsByGradeIdsAndUserId(user.getId(), ids,appUtil.ACTIVE).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getVb().equals(SCHOOLS)) {
				SIDs.addAll(studentService.findStudentsByCampusIdsAndUserId(user.getId(), ids,appUtil.ACTIVE).stream().map(Student::getId).collect(Collectors.toSet()));
			}
	        //getting student detail
			if(appUtil.isEmptyOrNull(SIDs))
				return new GenericResponse("FAILURE","Invalid input");
				
			List<Student> objs = studentService.findAllById(SIDs);
			objs.forEach(s ->{		
				final FeeCollectionDTO resDTO = new FeeCollectionDTO();
				resDTO.setUserId(user.getId());
				if(!appUtil.isEmptyOrNull(s.getSchoolId()))
					resDTO.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
				
				if(!appUtil.isEmptyOrNull(s.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(s.getGuardianId());
					if(g.isPresent()) {
						resDTO.setGn(g.get().getName());
						resDTO.setGId(g.get().getId());
					}
				}
				resDTO.setSn(s.getName());
				resDTO.setEn(s.getEnrollNo());
				if(!appUtil.isEmptyOrNull(s.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(s.getGradeId());
					if(grade.isPresent()) {
						resDTO.setGrId(grade.get().getId());
						resDTO.setG(grade.get().getName());
					}
				}
				resDTO.setVf(s.getVf()==null?0:s.getVf());
				resDTO.setF(s.getFee());
				resDTO.setDd(s.getDueDay());
				if(!appUtil.isEmptyOrNull(s.getDiscountId())) {
					Optional<Discount> d = discountService.findById(s.getDiscountId());
					if(d.isPresent()) {
						resDTO.setDt(d.get().getDi());
						resDTO.setD(d.get().getAmount());
					}else {
						resDTO.setDt(s.getDi());
						resDTO.setD(s.getNd());
					}
				}else {
					resDTO.setDt(s.getDi());
					resDTO.setD(s.getNd());
				}
				
				List<FeeCollection> fcList = null;
	        	try {
			        if(dto.getVp().intValue() == CURRENT_MONTH.intValue()) {
			        	fcList = feeCollectionService.findFCByDates(s.getEnrollNo(), appUtil.firstDateOfMonth(), appUtil.lastDateOfMonth(), user.getId());
			        }else if(!appUtil.isEmptyOrNull(dto.getSdStr()) && !appUtil.isEmptyOrNull(dto.getEdStr())) {
			        	fcList = feeCollectionService.findFCByDates(s.getEnrollNo(), appUtil.getLocalDateByMonthYear(dto.getSdStr()), appUtil.getLocalDateByMonthYear(dto.getEdStr()), user.getId());
			        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd())) {
			        	fcList = feeCollectionService.findFCByStartDate(s.getEnrollNo(), appUtil.getLocalDate(dto.getSdStr()), user.getId());
			        }else if(appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
			        	fcList = feeCollectionService.findFCByEndDate(s.getEnrollNo(), appUtil.getLocalDate(dto.getEdStr()), user.getId());
					}
				} catch (ParseException e1) {
					appUtil.le(this.getClass(),e1);
					e1.printStackTrace();
				}
				
				resDTO.setPdStr(appUtil.getLocalDateStr());
				//update list with last payment date where balance is due mean >0 
				//Note: need to implement DSL or some criteria
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				if(!appUtil.isEmptyOrNull(fcList)) {
					for (FeeCollection o : fcList) {
						L2.add(o);
					}
				}
				ML.add(new GenericResponse(resDTO,L2));
			});
			return new GenericResponse("SUCCESS",ML);
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",e.getMessage());
		}
	}
	
	@RequestMapping(value = "/loadFL", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse loadFL(final FeeVoucherDTO dto) {
		try {
			@SuppressWarnings({ "unchecked", "rawtypes" })
			List<GenericResponse> ML = new ArrayList();
			List<Long> SIDs = new ArrayList<Long>();
			User user = requestUtil.getCurrentUser();
			Student exp = new Student();
			exp.setUserId(user.getId());
			if(dto.getVb().equals(STUDENTS)) {
				exp.setId(Long.valueOf(dto.getVi()));
//				exp.setEnrollNo(dto.getVi());
				SIDs.add(studentService.findOne(Example.of(exp)).get().getId());
			}else if(dto.getVb().equals(GUARDIANS)) {
					exp.setGuardianId(Long.valueOf(dto.getVi()));
					SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getVb().equals(GRADES)) {
				exp.setGradeId(Long.valueOf(dto.getVi()));
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getVb().equals(SCHOOLS)) {
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}
//			if(dto.getVb()==0) {
//				exp.setEnrollNo(dto.getVi());
//				SIDs.add(studentService.findOne(Example.of(exp)).get().getId());
//			}else if(dto.getVb()==1) {
//					exp.setGuardianId(Long.valueOf(dto.getVi()));
//					SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
//			}else if(dto.getVb()==2) {
//				exp.setGradeId(Long.valueOf(dto.getVi()));
//				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
//			}
	        //getting student detail
			if(appUtil.isEmptyOrNull(SIDs))
				return new GenericResponse("FAILURE","Invalid input");
				
			List<Student> objs = studentService.findAllById(SIDs);
			objs.forEach(s ->{		
				final FeeCollectionDTO resDTO = new FeeCollectionDTO();
				resDTO.setUserId(user.getId());
				if(!appUtil.isEmptyOrNull(s.getSchoolId()))
					resDTO.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
				
				if(!appUtil.isEmptyOrNull(s.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(s.getGuardianId());
					if(g.isPresent()) {
						resDTO.setGn(g.get().getName());
						resDTO.setGId(g.get().getId());
					}
				}
				resDTO.setSn(s.getName());
				resDTO.setEn(s.getEnrollNo());
				if(!appUtil.isEmptyOrNull(s.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(s.getGradeId());
					if(grade.isPresent()) {
						resDTO.setGrId(grade.get().getId());
						resDTO.setG(grade.get().getName());
					}
				}
				resDTO.setVf(s.getVf()==null?0:s.getVf());
				resDTO.setF(s.getFee());
				resDTO.setDd(s.getDueDay());
				if(!appUtil.isEmptyOrNull(s.getDiscountId())) {
					Optional<Discount> d = discountService.findById(s.getDiscountId());
					if(d.isPresent()) {
						resDTO.setDt(d.get().getDi());
						resDTO.setD(d.get().getAmount());
					}else {
						resDTO.setDt(s.getDi());
						resDTO.setD(s.getNd());
					}
				}else {
					resDTO.setDt(s.getDi());
					resDTO.setD(s.getNd());
				}
				
				List<FeeCollection> fcList = null;
	        	try {
			        if(dto.getVp().intValue() == CURRENT_MONTH.intValue()) {
			        	fcList = feeCollectionService.findFCByDates(s.getEnrollNo(), appUtil.firstDateOfMonth(), appUtil.lastDateOfMonth(), user.getId());
			        }else if(!appUtil.isEmptyOrNull(dto.getSdStr()) && !appUtil.isEmptyOrNull(dto.getEdStr())) {
			        	fcList = feeCollectionService.findFCByDates(s.getEnrollNo(), appUtil.getLocalDate(dto.getSdStr()), appUtil.getLocalDate(dto.getEdStr()), user.getId());
			        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd())) {
			        	fcList = feeCollectionService.findFCByStartDate(s.getEnrollNo(), appUtil.getLocalDate(dto.getSdStr()), user.getId());
			        }else if(appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
			        	fcList = feeCollectionService.findFCByEndDate(s.getEnrollNo(), appUtil.getLocalDate(dto.getEdStr()), user.getId());
					}
				} catch (ParseException e1) {
					appUtil.le(this.getClass(),e1);
					e1.printStackTrace();
				}
				
				resDTO.setPdStr(appUtil.getLocalDateStr());
				//update list with last payment date where balance is due mean >0 
				//Note: need to implement DSL or some criteria
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				if(!appUtil.isEmptyOrNull(fcList)) {
					for (FeeCollection o : fcList) {
						L2.add(o);
					}
				}
				ML.add(new GenericResponse(resDTO,L2));
			});
			return new GenericResponse("SUCCESS",ML);
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",e.getMessage());
		}
	}
	
	@RequestMapping(value = "/loadFR", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse loadFR(final FeeVoucherDTO dto) {
		try {
			@SuppressWarnings({ "unchecked", "rawtypes" })
			List<GenericResponse> ML = new ArrayList();
			List<Long> SIDs = new ArrayList<Long>();
			User user = requestUtil.getCurrentUser();
			Student exp = new Student();
			exp.setUserId(user.getId());
			if(dto.getRb()==ENROLL_NO) {
				exp.setEnrollNo(dto.getRi());
//				SIDs.add(studentService.findOne(Example.of(exp)).get().getId());
			}else if(dto.getRb()==GUARDIAN) {
					exp.setGuardianId(Long.valueOf(dto.getRi()));
//					SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getRb()==GRADE) {
				exp.setGradeId(Long.valueOf(dto.getRi()));
//				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getRb()==CAMPUS) {
				exp.setSchoolId(Long.valueOf(dto.getRi()));
//				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else {
//				exp.setGradeId(Long.valueOf(dto.getVi()));
//				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}
			if(!appUtil.isEmptyOrNull(dto.getRbs())) {
				exp.setStatus(dto.getRbs());
//				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}
			SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
	        //getting student detail
//			if(AppUtil.isEmptyOrNull(SIDs))
//				return new GenericResponse("NOT_FOUND","NOT FOUND");
				
			List<Student> objs = studentService.findAllById(SIDs);
			objs.forEach(s ->{		
				final FeeCollectionDTO resDTO = new FeeCollectionDTO();
				resDTO.setUserId(user.getId());
				if(!appUtil.isEmptyOrNull(s.getSchoolId()))
					resDTO.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
				
				if(!appUtil.isEmptyOrNull(s.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(s.getGuardianId());
					if(g.isPresent()) {
						resDTO.setGn(g.get().getName());
						resDTO.setGId(g.get().getId());
					}
				}
				resDTO.setSn(s.getName());
				resDTO.setEn(s.getEnrollNo());
				if(!appUtil.isEmptyOrNull(s.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(s.getGradeId());
					if(grade.isPresent()) {
						resDTO.setGrId(grade.get().getId());
						resDTO.setG(grade.get().getName());
					}
				}
				resDTO.setVf(s.getVf()==null?0:s.getVf());
				resDTO.setF(s.getFee());
				resDTO.setDd(s.getDueDay());
				if(!appUtil.isEmptyOrNull(s.getDiscountId())) {
					Optional<Discount> d = discountService.findById(s.getDiscountId());
					if(d.isPresent()) {
						resDTO.setDt(d.get().getDi());
						resDTO.setD(d.get().getAmount());
					}else {
						resDTO.setDt(s.getDi());
						resDTO.setD(s.getNd());
					}
				}else {
					resDTO.setDt(s.getDi());
					resDTO.setD(s.getNd());
				}
				List<FeeCollection> fcList = null;
	        	try {
			        if(dto.getRp().intValue() == CURRENT_MONTH.intValue()) {
			        	fcList = feeCollectionService.findFCByDates(s.getEnrollNo(), appUtil.firstDateOfMonth(), appUtil.lastDateOfMonth(), user.getId());
			        }else if(!appUtil.isEmptyOrNull(dto.getSdStr()) && !appUtil.isEmptyOrNull(dto.getEdStr())) {
//							filter.setPd(appUtil.getLocalDate(dto.getSdStr()));
//			        	filter.setPd(appUtil.getLocalDate(dto.getEdStr()));
			        	fcList = feeCollectionService.findFCByDates(s.getEnrollNo(), appUtil.getLocalDate(dto.getSdStr()), appUtil.getLocalDate(dto.getEdStr()), user.getId());
			        }else if(!appUtil.isEmptyOrNull(dto.getSd()) && appUtil.isEmptyOrNull(dto.getEd())) {
//			        	filter.setPd(appUtil.getLocalDate(dto.getSdStr()));
			        	fcList = feeCollectionService.findFCByStartDate(s.getEnrollNo(), appUtil.getLocalDate(dto.getSdStr()), user.getId());
			        }else if(appUtil.isEmptyOrNull(dto.getSd()) && !appUtil.isEmptyOrNull(dto.getEd())) {
//			        	filter.setPd(appUtil.getLocalDate(dto.getEdStr()));
			        	fcList = feeCollectionService.findFCByEndDate(s.getEnrollNo(), appUtil.getLocalDate(dto.getEdStr()), user.getId());
					}
				} catch (ParseException e1) {
					appUtil.le(this.getClass(),e1);
					e1.printStackTrace();
				}
//				filter.setUserId(user.getId());
//				filter.setEn(s.getEnrollNo());
//		        Example<FeeCollection> exp2 = Example.of(filter);
		        
//				List<FeeCollection> L = feeCollectionService.findAll(exp2,appUtil.getPageRequest(appUtil.orderByDESC("pd"))).getContent();
//				if(!appUtil.isEmptyOrNull(L)) {
//					resDTO.setLpd(L.get(0).getPd());
//					resDTO.setDb(L.get(0).getDb());
//				}
				
				resDTO.setPdStr(appUtil.getLocalDateStr());
				//update list with last payment date where balance is due mean >0 
				//Note: need to implement DSL or some criteria
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				if(!appUtil.isEmptyOrNull(fcList)) {
					for (FeeCollection o : fcList) {
						L2.add(o);
					}
				}
				ML.add(new GenericResponse(resDTO,L2));
			});
			return new GenericResponse("SUCCESS",ML);
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",e.getMessage());
		}
	}

	@RequestMapping(value = "/getAllFc", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStudent(final HttpServletRequest request) {
		try {
			List<FeeCollection> objs = feeCollectionService.findAll();
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
	
	@RequestMapping(value = "/addFc", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addFc(@Validated final FeeCollectionDTO dto, final HttpServletRequest request) {
		try {
			FeeCollection obj = new FeeCollection();
			User user = requestUtil.getCurrentUser();
			obj  = modelMapper.map(dto, FeeCollection.class);
			obj.setUserId(user.getId());
			obj.setPd(appUtil.getLocalDate(dto.getPdStr()));
			obj.setFp((obj.getFp()==null?0:obj.getFp()) + (obj.getOd()==null?0:obj.getOd()));
//			if(!appUtil.isEmptyOrNull(obj.getFp()) && obj.getFp()>0)
//				obj.setDa(obj.getDa() - obj.getFp());
//			else
//				obj.setDa(obj.getDa() - obj.getFp());
			if(appUtil.isEmptyOrNull(feeCollectionService.save(obj)))
				return new GenericResponse("FAILED");

			return new GenericResponse("SUCCESS");
		} catch (Exception e) {
			e.printStackTrace();
			log.error(this.getClass().getName() +" >>> "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteFc", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteFc( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					feeCollectionService.deleteById(Long.valueOf(id));//.updateStatus("Inactive",id);//(Long.valueOf(id));
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

	@GetMapping("favicon.ico")
    @ResponseBody
    public String returnNoFavicon() {
		return "";
    }    
}
