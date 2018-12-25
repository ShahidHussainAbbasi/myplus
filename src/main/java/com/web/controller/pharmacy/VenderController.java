package com.web.controller.pharmacy;

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

import com.persistence.Repo.pharmacy.VenderRepo;
import com.persistence.model.pharmacy.Company;
import com.persistence.model.pharmacy.Item;
import com.persistence.model.pharmacy.Vender;
import com.security.ActiveUserStore;
import com.service.IAppointmentService;
import com.service.UserService;
import com.web.dto.pharmacy.VenderDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;

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
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",venders);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",venders);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
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
			Set<String> items = new HashSet<>();  
			venders.forEach(d -> {
				if(d!=null)
					items.add(d.getName());
				
			});
			sb.append("<option value='-1'> Select Vender </option>");
			items.forEach(d -> {
				if(d!=null)
					sb.append("<option value="+d+">"+((d!=null && d!="")?d:"-")+"</option>");
				
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
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",venders);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NOT_FOUND",venders);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addVender", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addVender(@Validated final VenderDTO venderDTO, final HttpServletRequest request) {
		try {
			Vender venderTemp = new Vender();
			venderTemp.setName(venderDTO.getName());
			Example<Vender> example = Example.of(venderTemp);
			if(venderRepo.exists(example)) {
				return new GenericResponse("FOUND",messages.getMessage("Vender "+"already.exist", null, request.getLocale()));
			}

			venderDTO.setUserId(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0)));
			venderDTO.setDated(AppUtil.todayDateStr());
			Vender vender = modelMapper.map(venderDTO, Vender.class);
			venderTemp = venderRepo.save(vender);
			if(venderTemp.getId()>0) {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS",venderTemp);
			}else {
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"FAILED",venderTemp);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
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
