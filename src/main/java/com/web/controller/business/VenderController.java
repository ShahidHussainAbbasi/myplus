package com.web.controller.business;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

import com.persistence.model.User;
import com.persistence.model.business.Vender;
import com.service.business.ICompanyService;
import com.service.business.IVenderService;
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
	IVenderService venderService;
	
	@Autowired
	ICompanyService companyService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserVender(final HttpServletRequest request) {
		try {
			Vender filterBy = new Vender();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Vender> example = Example.of(filterBy);
			List<Vender> objs = venderService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VenderDTO> dtos=new ArrayList<VenderDTO>(); 
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				dto.setCompanyId(obj.getCompany().getId());
				dto.setCompanyName(obj.getCompany().getName());
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserVender "+e.getCause());
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
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Vender> example = Example.of(filterBy);
			List<Vender> objs = venderService.findAll(example);
				
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null) {
					sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
				}
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getUserVenders "+e.getCause());			
			return (sb.append("<option value=''>No Data found</option>")).toString();
		}
	}

	@RequestMapping(value = "/getAllVender", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllVender(final HttpServletRequest request) {
		try {
			List<Vender> objs = venderService.findAll();
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));
			
			List<VenderDTO> dtos=new ArrayList<VenderDTO>(); 
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				dto.setCompanyId(obj.getCompany().getId());
				dto.setCompanyName(obj.getCompany().getName());
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),objs);
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > getAllVender "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addVender", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final VenderDTO dto, final HttpServletRequest request) {
		try {
			Vender obj= new Vender();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			obj.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				Example<Vender> example = Example.of(obj);
				if(venderService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Vender "+dto.getName()+" already exist", null, request.getLocale()));
			}
			
			obj = modelMapper.map(dto, Vender.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				obj.setDated(venderService.getOne(dto.getId()).getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);

			obj.setCompany(companyService.getOne(dto.getCompanyId()));
			obj = venderService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addVender "+e.getCause());			
			return new GenericResponse("ERROR",messages.getMessage(e.getMessage(), null, request.getLocale()),
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
					venderService.deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > deleteVender "+e.getCause());			
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
