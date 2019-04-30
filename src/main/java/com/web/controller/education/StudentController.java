package com.web.controller.education;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

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
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND");

			objs.forEach(obj ->{
				StudentDTO dto = new StudentDTO();
				dto = modelMapper.map(obj, StudentDTO.class);
				dto.setEnrollDate(appUtil.getLocalDateStr(obj.getEnrollDate()));
				dto.setDateOfBirth(appUtil.getLocalDateStr(obj.getDateOfBirth()));
				dto.setYs(appUtil.getLocalDateStr(obj.getYs()));
				dto.setYe(appUtil.getLocalDateStr(obj.getYe()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				if(!appUtil.isEmptyOrNull(obj.getSchoolId())) {
					Optional<School> school = schoolService.findById(obj.getSchoolId());
					if(school.isPresent()) {
						dto.setSchoolId(school.get().getId());
						dto.setSchoolName(school.get().getBranchName());
					}
				}
				if(!appUtil.isEmptyOrNull(obj.getGradeId())) {
					Optional<Grade> grade = gradeService.findById(obj.getGradeId());
					if(grade.isPresent()) {
						dto.setGradeId(grade.get().getId());
						dto.setGradeName(grade.get().getName());
					}
				}
				if(!appUtil.isEmptyOrNull(obj.getGuardianId())) {
					Optional<Guardian> g = guardianService.findById(obj.getGuardianId());
					if(g.isPresent()) {
						dto.setGuardianId(g.get().getId());
						dto.setGuardianName(g.get().getName());
					}
				}
				if(!appUtil.isEmptyOrNull(obj.getDiscountId())) {
					Optional<Discount> d = discountService.findById(obj.getDiscountId());
					if(d.isPresent()) {
						dto.setDiscountId(d.get().getId());
						dto.setDiscountName(d.get().getName());
						
						if(appUtil.isEmptyOrNull(obj.getNd()))
							dto.setNd(d.get().getAmount());
							if(appUtil.isEmptyOrNull(obj.getDi()))
								dto.setDi(d.get().getDi());
						
						
					}
				}
				if(!appUtil.isEmptyOrNull(obj.getVehicleId())) {
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
			if(appUtil.isEmptyOrNull(objs)){
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
			if(appUtil.isEmptyOrNull(dto.getId())){
				obj.setEnrollNo(dto.getEnrollNo());
				obj.setGuardianId(dto.getGuardianId());
				Example<Student> example = Example.of(obj);
				if(studentService.exists(example))
					return new GenericResponse("FOUND");
			}
			obj  = modelMapper.map(dto, Student.class);
			obj.setEnrollDate(appUtil.getLocalDate(dto.getEnrollDate()));
			obj.setDateOfBirth(appUtil.getLocalDate(dto.getDateOfBirth()));
			obj.setYs(appUtil.getLocalDate(dto.getYs()));
			obj.setYe(appUtil.getLocalDate(dto.getYe()));
			obj.setDated(dated);			
			obj.setUpdated(dated);

			if(appUtil.isEmptyOrNull(obj.getEmail()) || appUtil.isEmptyOrNull(obj.getMobile()) || appUtil.isEmptyOrNull(obj.getAddress())) {
				Optional<Guardian> g = guardianService.findById(obj.getGuardianId());
				if(g.isPresent()) {
					if(appUtil.isEmptyOrNull(obj.getEmail()))
						obj.setEmail(g.get().getEmail());
					if(appUtil.isEmptyOrNull(obj.getMobile()))
						obj.setMobile(g.get().getMobile());
					if(appUtil.isEmptyOrNull(obj.getAddress()))
						obj.setAddress(g.get().getPermAddress());
				}
			}
			if(appUtil.isEmptyOrNull(obj.getFee())){
				Optional<Grade> g = gradeService.findById(obj.getGradeId());
				if(g.isPresent())
					obj.setFee(g.get().getFee());
			}
//			obj.setSchoolId(dto.getSchoolId());
//			obj.setGradeId(dto.getGradeId());
//			if(dto.getGuardianId()>0)
//				obj.setGuardianId(dto.getGuardianId());
//
//			obj.setVehicleId(dto.getVehicleId());
			
			Student schoolOwnerTemp = studentService.save(obj);
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
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
	
	@SuppressWarnings({ "resource", "rawtypes" })
	@PostMapping("/impStudents")
	public void impStudents(@RequestParam("file") MultipartFile reapExcelDataFile){
		LocalDateTime dated = LocalDateTime.now();
		User user = requestUtil.getCurrentUser();
		try {
		    XSSFWorkbook workbook = new XSSFWorkbook (reapExcelDataFile.getInputStream());
		    XSSFSheet sheet = workbook.getSheetAt(0);
		    Iterator ite = sheet.rowIterator();
		    while(ite.hasNext()){
		    	Student obj = new Student();
		        Row row = (Row) ite.next();
		        if(row.getRowNum()==0 || row.getRowNum() == 1)
		        	continue;
		        
		        if(row.getCell(0)==null || row.getCell(0).getNumericCellValue() <= 0)
		        	break;
		       
		        Guardian g = new Guardian();
		        g.setUserId(user.getId());
				g.setName(row.getCell(14).getStringCellValue());
				Example<Guardian> g_example = Example.of(g);
		        List<Guardian> g2 = guardianService.findAll(g_example);
		        
		        if(g2==null || g2.size() <=0)
		        	continue;

		        Grade gr = new Grade();
		        gr.setUserId(user.getId());
		        gr.setName(row.getCell(13).getStringCellValue());
				Example<Grade> gr_example = Example.of(gr);
		        List<Grade> gr2 = gradeService.findAll(gr_example);
		        
		        if(gr2==null || gr2.size() <=0)
		        	continue;
		        
	        	//validate if already exist
		        obj.setUserId(user.getId());
  				if(row.getCell(16)!=null)
  					obj.setName(row.getCell(16).getStringCellValue().trim());
				Example<Student> example = Example.of(obj);
				if(studentService.exists(example))
	  		  		continue;
	  		  		
		        obj.setGradeId(gr2.get(0).getId());
		        obj.setGuardianId(g2.get(0).getId());
		        
		        String df = null;
		        
  				if(row.getCell(3)!=null && row.getCell(3).getDateCellValue()!=null) {
  			        df = new SimpleDateFormat("dd-MM-yyyy").format(row.getCell(3).getDateCellValue());
  					obj.setDateOfBirth(appUtil.getLocalDate(df.trim()));
  				}
  				if(row.getCell(8)!=null &&row.getCell(8).getDateCellValue()!=null) {
  			        df = new SimpleDateFormat("dd-MM-yyyy").format(row.getCell(8).getDateCellValue());
  					obj.setEnrollDate(appUtil.getLocalDate(df.trim()));  					
  				}
				obj.setDated(dated);
  				obj.setUpdated(dated);
  				if(row.getCell(10)!=null)
  				obj.setFee((float) row.getCell(10).getNumericCellValue());
  				
  				if(row.getCell(27)!=null)
				obj.setMn(row.getCell(27).getStringCellValue().trim());
  				if(row.getCell(38)!=null)
				obj.setPob(row.getCell(38).getStringCellValue().trim());
  				if(row.getCell(29)!=null)
				obj.setReligion(row.getCell(29).getStringCellValue().trim());

  				obj.setStatus(appUtil.ACTIVE);
  				obj = studentService.save(obj);
  				
  				if(appUtil.isEmptyOrNull(obj)) {
  					System.out.println(obj);
  				}else {
  					System.out.println();
  				}
	  		  		
		    }
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			
		}    
	}
	
	
}
