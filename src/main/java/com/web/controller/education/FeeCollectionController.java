package com.web.controller.education;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);

			objs.forEach(obj ->{
				FeeCollectionDTO dto = new FeeCollectionDTO();
				dto = modelMapper.map(obj, FeeCollectionDTO.class);
				dto.setDd(obj.getDd());
				dto.setPdStr(AppUtil.getLocalDateStr(obj.getPd()));
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
			if(!AppUtil.isEmptyOrNull(s.getSchoolId()))
				dto.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
			
			if(!AppUtil.isEmptyOrNull(s.getGuardianId())) {
				Optional<Guardian> g = guardianService.findById(s.getGuardianId());
				if(g.isPresent())
					dto.setGn(g.get().getName());
			}
			dto.setSn(s.getName());
			if(!AppUtil.isEmptyOrNull(s.getGradeId())) {
				Optional<Grade> grade = gradeService.findById(s.getGradeId());
				if(grade.isPresent())
					dto.setG(grade.get().getName());
			}
			dto.setVf(s.getVf()==null?0:s.getVf());
			dto.setF(s.getFee());
			dto.setDd(s.getDueDay());
			if(!AppUtil.isEmptyOrNull(s.getDiscountId())) {
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
			List<FeeCollection> L = feeCollectionService.findAll(e,AppUtil.getPageRequest(AppUtil.orderByDESC("pd"))).getContent();
			if(!AppUtil.isEmptyOrNull(L)) {
				dto.setLpd(L.get(0).getPd());
				dto.setDb(L.get(0).getDb());
			}
			
			dto.setPdStr(AppUtil.getLocalDateStr());
			fcm.put("sf", dto);
			//rest of detail
			
			//update list with last payment date where balance is due mean >0 
			//Note: need to implement DSL or some criteria
			if(!AppUtil.isEmptyOrNull(L)) {
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
			AppUtil.le(this.getClass(),e);
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
			User user = requestUtil.getCurrentUser();
			Student exp = new Student();
			exp.setUserId(user.getId());
			if(dto.getVb()==0) {
				exp.setEnrollNo(dto.getVi());
				SIDs.add(studentService.findOne(Example.of(exp)).get().getId());
			}else if(dto.getVb()==1) {
					exp.setGuardianId(Long.valueOf(dto.getVi()));
					SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getVb()==2) {
				exp.setGradeId(Long.valueOf(dto.getVi()));
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}
	        //getting student detail
			if(AppUtil.isEmptyOrNull(SIDs))
				return new GenericResponse("FAILURE","Invalid input");
				
			List<Student> objs = studentService.findAllById(SIDs);
			objs.forEach(s ->{		
				final FeeCollectionDTO resDTO = new FeeCollectionDTO();
				resDTO.setUserId(user.getId());
				if(!AppUtil.isEmptyOrNull(s.getSchoolId()))
					resDTO.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
				
				if(!AppUtil.isEmptyOrNull(s.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(s.getGuardianId());
					if(g.isPresent()) {
						resDTO.setGn(g.get().getName());
						resDTO.setGId(g.get().getId());
					}
				}
				resDTO.setSn(s.getName());
				resDTO.setEn(s.getEnrollNo());
				if(!AppUtil.isEmptyOrNull(s.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(s.getGradeId());
					if(grade.isPresent()) {
						resDTO.setGrId(grade.get().getId());
						resDTO.setG(grade.get().getName());
					}
				}
				resDTO.setVf(s.getVf()==null?0:s.getVf());
				resDTO.setF(s.getFee());
				resDTO.setDd(s.getDueDay());
				if(!AppUtil.isEmptyOrNull(s.getDiscountId())) {
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
				
				FeeCollection f = new FeeCollection();
				f.setUserId(user.getId());
				f.setEn(s.getEnrollNo());
		        Example<FeeCollection> e = Example.of(f);
				List<FeeCollection> L = feeCollectionService.findAll(e,AppUtil.getPageRequest(AppUtil.orderByDESC("pd"))).getContent();
				if(!AppUtil.isEmptyOrNull(L)) {
					resDTO.setLpd(L.get(0).getPd());
					resDTO.setDb(L.get(0).getDb());
				}
				
				resDTO.setPdStr(AppUtil.getLocalDateStr());
				//update list with last payment date where balance is due mean >0 
				//Note: need to implement DSL or some criteria
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				if(!AppUtil.isEmptyOrNull(L)) {
					for (FeeCollection o : L) {
						if(o.getDb()>0)
							L2.add(o);
						else {
							L2.add(o);//last payment
							break;
						}
					}
				}
				ML.add(new GenericResponse(resDTO,L2));
			});
			return new GenericResponse("SUCCESS",ML);
		} catch (Exception e) {
			e.printStackTrace();
			AppUtil.le(this.getClass(),e);
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
			if(dto.getVb()==0) {
				exp.setEnrollNo(dto.getVi());
				SIDs.add(studentService.findOne(Example.of(exp)).get().getId());
			}else if(dto.getVb()==1) {
					exp.setGuardianId(Long.valueOf(dto.getVi()));
					SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getVb()==2) {
				exp.setGradeId(Long.valueOf(dto.getVi()));
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}
	        //getting student detail
			if(AppUtil.isEmptyOrNull(SIDs))
				return new GenericResponse("FAILURE","Invalid input");
				
			List<Student> objs = studentService.findAllById(SIDs);
			objs.forEach(s ->{		
				final FeeCollectionDTO resDTO = new FeeCollectionDTO();
				resDTO.setUserId(user.getId());
				if(!AppUtil.isEmptyOrNull(s.getSchoolId()))
					resDTO.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
				
				if(!AppUtil.isEmptyOrNull(s.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(s.getGuardianId());
					if(g.isPresent()) {
						resDTO.setGn(g.get().getName());
						resDTO.setGId(g.get().getId());
					}
				}
				resDTO.setSn(s.getName());
				resDTO.setEn(s.getEnrollNo());
				if(!AppUtil.isEmptyOrNull(s.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(s.getGradeId());
					if(grade.isPresent()) {
						resDTO.setGrId(grade.get().getId());
						resDTO.setG(grade.get().getName());
					}
				}
				resDTO.setVf(s.getVf()==null?0:s.getVf());
				resDTO.setF(s.getFee());
				resDTO.setDd(s.getDueDay());
				if(!AppUtil.isEmptyOrNull(s.getDiscountId())) {
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
				
				FeeCollection f = new FeeCollection();
				f.setUserId(user.getId());
				f.setEn(s.getEnrollNo());
		        Example<FeeCollection> e = Example.of(f);
				List<FeeCollection> L = feeCollectionService.findAll(e,AppUtil.getPageRequest(AppUtil.orderByDESC("pd"))).getContent();
				if(!AppUtil.isEmptyOrNull(L)) {
					resDTO.setLpd(L.get(0).getPd());
					resDTO.setDb(L.get(0).getDb());
				}
				
				resDTO.setPdStr(AppUtil.getLocalDateStr());
				//update list with last payment date where balance is due mean >0 
				//Note: need to implement DSL or some criteria
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				if(!AppUtil.isEmptyOrNull(L)) {
					for (FeeCollection o : L) {
						L2.add(o);
					}
				}
				ML.add(new GenericResponse(resDTO,L2));
			});
			return new GenericResponse("SUCCESS",ML);
		} catch (Exception e) {
			e.printStackTrace();
			AppUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",e.getMessage());
		}
	}
	
	@RequestMapping(value = "/getUserFR", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse loadFR(final FeeVoucherDTO dto) {
		try {
			@SuppressWarnings({ "unchecked", "rawtypes" })
			List<GenericResponse> ML = new ArrayList();
			List<Long> SIDs = new ArrayList<Long>();
			User user = requestUtil.getCurrentUser();
			Student exp = new Student();
			exp.setUserId(user.getId());
			if(dto.getRb()==0) {
				exp.setEnrollNo(dto.getRi());
				SIDs.add(studentService.findOne(Example.of(exp)).get().getId());
			}else if(dto.getRb()==1) {
					exp.setGuardianId(Long.valueOf(dto.getRi()));
					SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getRb()==2) {
				exp.setGradeId(Long.valueOf(dto.getRi()));
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else if(dto.getRb()==3) {
				exp.setSchoolId(Long.valueOf(dto.getRi()));
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}else {
//				exp.setGradeId(Long.valueOf(dto.getVi()));
				SIDs.addAll(studentService.findAll(Example.of(exp)).stream().map(Student::getId).collect(Collectors.toSet()));
			}
	        //getting student detail
			if(AppUtil.isEmptyOrNull(SIDs))
				return new GenericResponse("FAILURE","Invalid input");
				
			List<Student> objs = studentService.findAllById(SIDs);
			objs.forEach(s ->{		
				final FeeCollectionDTO resDTO = new FeeCollectionDTO();
				resDTO.setUserId(user.getId());
				if(!AppUtil.isEmptyOrNull(s.getSchoolId()))
					resDTO.setScn(schoolService.findById(s.getSchoolId()).get().getBranchName());
				
				if(!AppUtil.isEmptyOrNull(s.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(s.getGuardianId());
					if(g.isPresent()) {
						resDTO.setGn(g.get().getName());
						resDTO.setGId(g.get().getId());
					}
				}
				resDTO.setSn(s.getName());
				resDTO.setEn(s.getEnrollNo());
				if(!AppUtil.isEmptyOrNull(s.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(s.getGradeId());
					if(grade.isPresent()) {
						resDTO.setGrId(grade.get().getId());
						resDTO.setG(grade.get().getName());
					}
				}
				resDTO.setVf(s.getVf()==null?0:s.getVf());
				resDTO.setF(s.getFee());
				resDTO.setDd(s.getDueDay());
				if(!AppUtil.isEmptyOrNull(s.getDiscountId())) {
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
				
				FeeCollection f = new FeeCollection();
				f.setUserId(user.getId());
				f.setEn(s.getEnrollNo());
		        Example<FeeCollection> e = Example.of(f);
				List<FeeCollection> L = feeCollectionService.findAll(e,AppUtil.getPageRequest(AppUtil.orderByDESC("pd"))).getContent();
				if(!AppUtil.isEmptyOrNull(L)) {
					resDTO.setLpd(L.get(0).getPd());
					resDTO.setDb(L.get(0).getDb());
				}
				
				resDTO.setPdStr(AppUtil.getLocalDateStr());
				//update list with last payment date where balance is due mean >0 
				//Note: need to implement DSL or some criteria
				List<FeeCollection> L2=new ArrayList<FeeCollection>();
				if(!AppUtil.isEmptyOrNull(L)) {
					for (FeeCollection o : L) {
						L2.add(o);
					}
				}
				ML.add(new GenericResponse(resDTO,L2));
			});
			return new GenericResponse("SUCCESS",ML);
		} catch (Exception e) {
			e.printStackTrace();
			AppUtil.le(this.getClass(),e);
			return new GenericResponse("ERROR",e.getMessage());
		}
	}

	@RequestMapping(value = "/getAllFc", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllStudent(final HttpServletRequest request) {
		try {
			List<FeeCollection> objs = feeCollectionService.findAll();
			if(AppUtil.isEmptyOrNull(objs)){
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
			obj.setPd(AppUtil.getLocalDate(dto.getPdStr()));
			
			if(AppUtil.isEmptyOrNull(feeCollectionService.save(obj)))
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
}
