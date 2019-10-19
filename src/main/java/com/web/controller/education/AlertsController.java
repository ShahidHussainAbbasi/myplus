package com.web.controller.education;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Example;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.persistence.Repo.education.AlertChannelRepo;
import com.persistence.model.User;
import com.persistence.model.education.AlertChannel;
import com.persistence.model.education.Alerts;
import com.persistence.model.education.Guardian;
import com.persistence.model.education.Staff;
import com.service.education.IAlertsService;
import com.service.education.IGuardianService;
import com.service.education.IStaffService;
import com.web.dto.education.AlertChannelDTO;
import com.web.dto.education.AlertsDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.ObjectMapperUtils;
import com.web.util.RequestUtil;

@Controller
public class AlertsController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;

	@Autowired
	IAlertsService service;

	@Autowired
	IStaffService staffService;
	
	@Autowired
	IGuardianService guardianService;

	@Autowired
	RequestUtil requestUtil;
	
    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private Environment env;
	
    @Autowired
    AppUtil appUtil;  
    
    @Autowired
    AlertChannelRepo alertChannelRepo;
    
	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserAlerts", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserAlerts(final HttpServletRequest request) {
		try {
			Alerts filterBy = new Alerts();
			User user = requestUtil.getCurrentUser();
			filterBy.setuId(user.getId());
	        Example<Alerts> example = Example.of(filterBy);
			List<Alerts> objs = service.findAll(example);
			Set<AlertsDTO> dtos = new HashSet<AlertsDTO>();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);

			objs.forEach(obj ->{
				AlertsDTO dto = new AlertsDTO();
				dto = modelMapper.map(obj, AlertsDTO.class);
				dto.setSdStr(appUtil.getLocalDateStr(obj.getSd()));
				dto.setEdStr(appUtil.getLocalDateStr(obj.getEd()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserAlertss", method = RequestMethod.GET)
	@ResponseBody
	public String getUserAlertss(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Alerts filterBy = new Alerts();
			User user = requestUtil.getCurrentUser();
			filterBy.setuId(user.getId());
	        Example<Alerts> example = Example.of(filterBy);
			List<Alerts> objs = service.findAll(example);
			sb.append("<option value=''> Nothing Selected </option>");
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getId()+">"+d.getAh()+"</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllAlerts", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllAlerts(final HttpServletRequest request) {
		try {
			List<Alerts> objs = service.findAll();
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
	
	@RequestMapping(value = "/addAlerts", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addAlerts(@Validated final AlertsDTO dto, final HttpServletRequest request) {
		try {
			Alerts obj = new Alerts();
			User user = requestUtil.getCurrentUser();
			dto.setuId(user.getId());
			obj.setuId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				obj.setAh(dto.getAh());
				Example<Alerts> example = Example.of(obj);
				if(service.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Alerts "+dto.getAh()+" already exist", null, request.getLocale()));
			}
			obj  = modelMapper.map(dto, Alerts.class);
			obj.setSd(appUtil.getLocalDate(dto.getSdStr()));
			obj.setEd(appUtil.getLocalDate(dto.getEdStr()));
			
			Alerts schoolOwnerTemp = service.save(obj);
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
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
	
	@RequestMapping(value = "/deleteAlerts", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse deleteAlerts(final HttpServletRequest request){
		try {
		String ids = request.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					service.deleteById(Long.valueOf(id));//.updateStatus("Inactive",id);//(Long.valueOf(id));
				}
				return new GenericResponse("OK");
			}else {
				return new GenericResponse("FAILURE");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" >>> "+e.getCause());
			return new GenericResponse("ERROR",messages.getMessage(e.getCause()+"", null, request.getLocale()));
		}
	}

	@RequestMapping(value = "/sendAlerts", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse sendAlerts(@Validated final AlertsDTO dto,final HttpServletRequest request){
		try {
			String[] c = dto.getC().split(",");
			for(int i=0; i<c.length;i++) {
				if(c[i].equals("Employees")) {
					Staff f = new Staff();
					User user = requestUtil.getCurrentUser();
					f.setUserId(user.getId());
					f.setStatus(appUtil.ACTIVE);
			        Example<Staff> example = Example.of(f);
					List<Staff> objs = staffService.findAll(example);
					if(appUtil.isEmptyOrNull(objs))
						continue;
					objs.forEach(o ->{
						
				        final String recipientAddress = o.getEmail();
				        final String subject = dto.getAh();
//				        final String message = messages.getMessage(dto.getAm(),request.getLocale());
				        final SimpleMailMessage email = new SimpleMailMessage();
				        email.setTo(recipientAddress);
				        email.setSubject(subject);
				        email.setText("Dear "+o.getName()+ " \r\n\n" +dto.getAm() + " \r\n\n Best Regards\n" + dto.getAs());
				        email.setFrom(env.getProperty("support.email"));
				        mailSender.send(email);					
					});
				}else if(c[i].equals("Guardians")) {
					Guardian g = new Guardian();
					User user = requestUtil.getCurrentUser();
					g.setUserId(user.getId());
					g.setStatus(appUtil.ACTIVE);
			        Example<Guardian> example = Example.of(g);
					List<Guardian> objs = guardianService.findAll(example);
					if(appUtil.isEmptyOrNull(objs))
						continue;
					objs.forEach(o ->{
						
				        final String recipientAddress = o.getEmail();
				        final String subject = dto.getAh();
//				        final String message = messages.getMessage(dto.getAm(),request.getLocale());
				        final SimpleMailMessage email = new SimpleMailMessage();
				        email.setTo(recipientAddress);
				        email.setSubject(subject);
				        email.setText("Dear "+o.getName()+ " \r\n\n" +dto.getAm() + " \r\n\n Best Regards\n" + dto.getAs());
				        email.setFrom(env.getProperty("support.email"));
				        mailSender.send(email);					
						appUtil.li(this.getClass(), "Email sent successfully to "+recipientAddress);
					});
				}
			}
//			AppUtil.li(this.getClass(), "Email sent successfully");
			return new GenericResponse(appUtil.SUCCESS);
		} catch (Exception e) {
//			e.printStackTrace();
			appUtil.le(this.getClass(), e);//.error(this.getClass().getName()+" >>> "+e.getCause());
			return new GenericResponse(appUtil.ERROR,messages.getMessage(e.getCause()+"", null, request.getLocale()));
		}
	}
	
//	@PostMapping("/importCSV")
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@RequestMapping(value = "/importCSV", method = RequestMethod.POST)
	public ResponseEntity<?> importCSV(@RequestParam("file") MultipartFile multipart){
		if (multipart.isEmpty()) {
            return new ResponseEntity("please select a file!", HttpStatus.OK);
        }
		LocalDateTime dated = LocalDateTime.now();
		User user = requestUtil.getCurrentUser();
		List<String> invalids = new ArrayList();
		BufferedReader br;
		try {
//            byte[] bytes = multipart.getBytes();
//            String completeData = new String(bytes);
//            String[] rows = completeData.split("#");
//            String[] columns = rows[0].split(",");			
		     br = new BufferedReader(new InputStreamReader(multipart.getInputStream()));
			//br returns as stream and convert it into a List
			Stream<String> stream  = br.lines();
			stream.forEach(line ->{
				if(!line.equals("")) {
					final Stream<String> stream2 = Arrays.stream(line.split(","));
					stream2.forEach(item ->{
						try {
							AlertChannel ac = new AlertChannel();
							if(appUtil.validateEmail(item)) {
								ac.setC(item);
								ac.setCn("Email");
								ac.setS("Valid");
							}else if(appUtil.validateMobileNumber(item)) {
								ac.setC(item);
								ac.setCn("Mobile");
								ac.setS("Valid");
							}else {
								invalids.add(item);
							}
							ac.setUId(user.getId());
							Example<AlertChannel> example = Example.of(ac);
							if(!appUtil.isEmptyOrNull(ac.getS())&& !alertChannelRepo.exists(example)) {
								ac.setDt(dated);
								ac.setUt("EDUCATION");
								alertChannelRepo.save(ac);
							}
						  } catch (Exception e) {
							  appUtil.le(this.getClass(), e);      
						  }
				    });
				}
			});
//			alertChannelRepo.saveAll(acL);
			return new ResponseEntity("File successfully imported!", HttpStatus.OK);
		  } catch (Exception e) {
			  appUtil.le(this.getClass(), e);      
			  return new ResponseEntity("File import error! "+e.getClass().toString(), HttpStatus.EXPECTATION_FAILED);
		  }
	}

	@RequestMapping(value = "/getUserPA", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserpa(final HttpServletRequest request) {
		try {
			AlertChannel filterBy = new AlertChannel();
			User user = requestUtil.getCurrentUser();
			filterBy.setUId(user.getId());
	        Example<AlertChannel> example = Example.of(filterBy);
			List<AlertChannel> objs = alertChannelRepo.findAll(example);
			List<AlertChannelDTO> DTOs = new ArrayList<>();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND","No data available",objs);

			DTOs = ObjectMapperUtils.mapAll(objs, AlertChannelDTO.class);
			DTOs.forEach(dto ->{
				dto.setDtStr(appUtil.getDateTimeStr(dto.getDt()));
			});
			return new GenericResponse("SUCCESS","Data loaded successfully",DTOs);
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse("ERROR",e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/sendPA", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse sendPA(@Validated final AlertChannelDTO dto,final HttpServletRequest request){
		try {
			List<AlertChannel> emails = new ArrayList<>();
//			List<AlertChannel> mobiles = new ArrayList<>();
			AlertChannel ac = new AlertChannel();
			User user = requestUtil.getCurrentUser();
			ac.setUId(user.getId());
			if(dto.getIsEmail() && !dto.getIsAll() && !dto.getIsMobile()) {
				ac.setCn("Email");
		        Example<AlertChannel> example = Example.of(ac);
		        emails = alertChannelRepo.findAll(example);
			}else if(dto.getIsMobile() && !dto.getIsAll() && !dto.getIsEmail()) {
				ac.setCn("Email");
		        Example<AlertChannel> example = Example.of(ac);
		        emails = alertChannelRepo.findAll(example);
			}else {
		        Example<AlertChannel> example = Example.of(ac);
				List<AlertChannel> objs = alertChannelRepo.findAll(example);
				emails = objs.stream().filter(o -> "Email".equals(o.getCn())).collect(Collectors.toList()); 
//				mobiles = objs.stream().filter(o -> "Mobile".equals(o.getCn())).collect(Collectors.toList()); 
			}
			emails.forEach(o -> {
		        final String recipientAddress = o.getC();
		        final String subject = dto.getPah();
		        final SimpleMailMessage email = new SimpleMailMessage();
		        email.setTo(recipientAddress);
		        email.setSubject(subject);
		        email.setText(dto.getPam() + " \r\n\n Best Regards\n" + dto.getPas());
		        email.setFrom(env.getProperty("support.email"));
		        mailSender.send(email);					
			});
			return new GenericResponse(appUtil.SUCCESS);
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);//.error(this.getClass().getName()+" >>> "+e.getCause());
			return new GenericResponse(appUtil.ERROR,messages.getMessage(e.getCause()+"", null, request.getLocale()));
		}
	}
	
}
