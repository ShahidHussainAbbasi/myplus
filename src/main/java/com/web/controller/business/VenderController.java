package com.web.controller.business;

import java.util.List;

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

import com.persistence.Repo.business.VenderRepo;
import com.persistence.model.User;
import com.persistence.model.business.Vender;
import com.security.ActiveUserStore;
import com.service.IAppointmentService;
import com.service.UserService;
import com.web.dto.business.VenderDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class VenderController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;
	@Autowired
	ActiveUserStore activeUserStore;

	@Autowired
	IAppointmentService appointmentService;

	@Autowired
	VenderRepo venderRepo;

	@Autowired
	UserService userService;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserVender(final HttpServletRequest request) {
		try {
			Vender filterBy = new Vender();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<Vender> example = Example.of(filterBy);
			List<Vender> venders = venderRepo.findAll(example);
			if(venders.size()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),venders);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),venders);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserVenders", method = RequestMethod.GET)
	@ResponseBody
	public String getUserVenders(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Vender filterBy = new Vender();
			filterBy.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
	        Example<Vender> example = Example.of(filterBy);
			List<Vender> venders = venderRepo.findAll(example);
			sb.append("<option value=''> Select Vender </option>");
			venders.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value="+d.getName()+">"+d.getName()+"</option>");
//				sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
				
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllVenders(final HttpServletRequest request) {
		try {
			List<Vender> venders = venderRepo.findAll();
			if(venders.size()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),venders);
			}else {
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),venders);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addVender", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addVender(@Validated final VenderDTO venderDTO, final HttpServletRequest request) {
		try {
			Vender vender = new Vender();
			User user = requestUtil.getCurrentUser();
			venderDTO.setUserId(user.getId());
			venderDTO.setUserType(user.getUserType());
			vender = modelMapper.map(venderDTO, Vender.class);
			if(vender.getId()!=null && vender.getId()>0) {
				Example<Vender> example = Example.of(vender);
				if(venderRepo.exists(example)) {
					return new GenericResponse("FOUND",messages.getMessage("Your vender already exist", null, request.getLocale()));
				}
			}
			vender.setDated(AppUtil.todayDateStr());
			vender = venderRepo.save(vender);
			if(vender.getId()>0) {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),vender);
			}else {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()),vender);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteVender", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteVender( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					venderRepo.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
//			companyRepo.deleteById(id);delete(company);
		} catch (Exception e) {
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
