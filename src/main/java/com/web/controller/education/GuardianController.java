package com.web.controller.education;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
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
import com.persistence.model.education.Guardian;
import com.service.education.IGuardianService;
import com.web.dto.education.GuardianDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class GuardianController {

	@Autowired
	private MessageSource messages;

	@Autowired
	IGuardianService guardianService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserGuardian", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserGuardian(final HttpServletRequest request) {
		try {
			Guardian filterBy = new Guardian();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
			List<Guardian> objs = guardianService.findAll(Example.of(filterBy));
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			
			List<GuardianDTO> dtos = new ArrayList<>();
			objs.forEach(obj->{
				GuardianDTO dto = new GuardianDTO();
				dto = modelMapper.map(obj, GuardianDTO.class);
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
	
	@RequestMapping(value = "/getUserGuardians", method = RequestMethod.GET)
	@ResponseBody
	public String getUserGuardians(final HttpServletRequest request) {
//		importGuardian();
		StringBuffer sb = new StringBuffer();
		try {
			Guardian filterBy = new Guardian();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Guardian> example = Example.of(filterBy);
			List<Guardian> objs = guardianService.findAll(example);
			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getId()+">"+d.getName()+"-"+d.getId()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllGuardian", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllGuardian(final HttpServletRequest request) {
		try {
//			importGuardian();
			List<Guardian> objs = guardianService.findAll();
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addGuardian", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addGuardian(@Validated final GuardianDTO dto, final HttpServletRequest request) {
		try {
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			Guardian obj = new Guardian();
			dto.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())) {
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				Example<Guardian> example = Example.of(obj);
				if(guardianService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("message.exist", null, request.getLocale()));
			}

			obj  = modelMapper.map(dto, Guardian.class);
			obj.setDated(dated);
			obj.setUpdated(dated);
			obj = guardianService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.fail_saveOrUpdate", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.success_saveOrUpdate", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.system_error "+e.getMessage(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteGuardian", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse deleteGuardian(final HttpServletRequest request){
		try {
		String ids = request.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					guardianService.deleteById(Long.valueOf(id));
				}
				return new GenericResponse("SUCCESS",messages.getMessage("message.success_delete", null, request.getLocale()));
			}else {
				return new GenericResponse("FAILED",messages.getMessage("message.fail_delete", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",messages.getMessage("message.e", null, request.getLocale()));
		}
	}
	
	@SuppressWarnings({ "resource", "rawtypes" })
	@PostMapping("/impG")
	public void mapReapExcelDatatoDB(@RequestParam("file") MultipartFile reapExcelDataFile){
//    public void importGuardian() {
		LocalDateTime dated = LocalDateTime.now();
		User user = requestUtil.getCurrentUser();
		try {
		    XSSFWorkbook workbook = new XSSFWorkbook (reapExcelDataFile.getInputStream());
		    XSSFSheet sheet = workbook.getSheetAt(0);
		    Iterator ite = sheet.rowIterator();
		    while(ite.hasNext()){
		    	Guardian obj = new Guardian();
		        Row row = (Row) ite.next();
		        if(row.getRowNum()==0)
		        	continue;
		        if(row.getRowNum()==155)
		        	break;
		        
	        	//validate if already exist
		        obj.setUserId(user.getId());
				obj.setName(row.getCell(5).getStringCellValue());
				Example<Guardian> example = Example.of(obj);
				if(guardianService.exists(example))
	  		  		continue;
	  		  		
  				obj.setDated(dated);
  				if(row.getCell(2)!=null)
				obj.setEmail(row.getCell(2).getStringCellValue().trim());
  				if(row.getCell(3)!=null)
				obj.setGender(row.getCell(3).getStringCellValue().trim());
  				if(row.getCell(4)!=null)
				obj.setMobile(row.getCell(4).getStringCellValue().trim());
  				if(row.getCell(5)!=null)
				obj.setName(row.getCell(5).getStringCellValue().trim());
  				if(row.getCell(6)!=null)
				obj.setOccupation(row.getCell(6).getStringCellValue().trim());
  				if(row.getCell(7)!=null)
				obj.setPermAddress(row.getCell(7).getStringCellValue().trim());
  				if(row.getCell(8)!=null)
				obj.setPhone(row.getCell(8).getStringCellValue().trim());
  				if(row.getCell(9)!=null)
				obj.setRelation(row.getCell(9).getStringCellValue().trim());
  				if(row.getCell(10)!=null)
				obj.setStatus(row.getCell(10).getStringCellValue().trim());
  				if(row.getCell(11)!=null)
				obj.setTempAddress(row.getCell(11).getStringCellValue().trim());
  				obj.setUpdated(dated);
  				if(row.getCell(14)!=null)
				obj.setCnic(row.getCell(14).getStringCellValue().trim());
  				obj = guardianService.save(obj);
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
