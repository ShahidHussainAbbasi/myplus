package com.web.controller.education;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Example;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.education.Alerts;
import com.persistence.model.education.Guardian;
import com.persistence.model.education.Staff;
import com.service.education.IAlertsService;
import com.service.education.IGuardianService;
import com.service.education.IStaffService;
import com.web.dto.education.AlertsDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
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
			if(AppUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);

			objs.forEach(obj ->{
				AlertsDTO dto = new AlertsDTO();
				dto = modelMapper.map(obj, AlertsDTO.class);
				dto.setSdStr(AppUtil.getLoaclDateStr(obj.getSd()));
				dto.setEdStr(AppUtil.getLoaclDateStr(obj.getEd()));
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
	
	@RequestMapping(value = "/addAlerts", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addAlerts(@Validated final AlertsDTO dto, final HttpServletRequest request) {
		try {
			Alerts obj = new Alerts();
			User user = requestUtil.getCurrentUser();
			dto.setuId(user.getId());
			obj.setuId(user.getId());
			if(AppUtil.isEmptyOrNull(dto.getId())){
				obj.setAh(dto.getAh());
				Example<Alerts> example = Example.of(obj);
				if(service.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Alerts "+dto.getAh()+" already exist", null, request.getLocale()));
			}
			obj  = modelMapper.map(dto, Alerts.class);
			obj.setSd(AppUtil.getLocalDate(dto.getSdStr()));
			obj.setEd(AppUtil.getLocalDate(dto.getEdStr()));
			
			Alerts schoolOwnerTemp = service.save(obj);
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
					f.setStatus(AppUtil.ACTIVE);
			        Example<Staff> example = Example.of(f);
					List<Staff> objs = staffService.findAll(example);
					if(AppUtil.isEmptyOrNull(objs))
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
					g.setStatus(AppUtil.ACTIVE);
			        Example<Guardian> example = Example.of(g);
					List<Guardian> objs = guardianService.findAll(example);
					if(AppUtil.isEmptyOrNull(objs))
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
						AppUtil.li(this.getClass(), "Email sent successfully to "+recipientAddress);
					});
				}
			}
//			AppUtil.li(this.getClass(), "Email sent successfully");
			return new GenericResponse(AppUtil.SUCCESS);
		} catch (Exception e) {
			e.printStackTrace();
			AppUtil.le(this.getClass(), e);//.error(this.getClass().getName()+" >>> "+e.getCause());
			return new GenericResponse(AppUtil.ERROR,messages.getMessage(e.getCause()+"", null, request.getLocale()));
		}
	}
}
