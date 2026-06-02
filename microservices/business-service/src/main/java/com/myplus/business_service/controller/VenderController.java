package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

import com.myplus.business_service.security.AuthenticatedUser;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.service.ICompanyService;
import com.myplus.business_service.service.IVenderService;
import com.myplus.business_service.dto.VenderDTO;
import com.myplus.business_service.util.AppUtil;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.business_service.util.RequestUtil;

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
			AuthenticatedUser user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getUserId());
	        Example<Vender> example = Example.of(filterBy);
			List<Vender> objs = venderService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<VenderDTO> dtos=new ArrayList<VenderDTO>(); 
			objs.forEach(obj ->{
				VenderDTO dto = modelMapper.map(obj, VenderDTO.class);
				if(!appUtil.isEmptyOrNull(obj.getCompany())) {
					dto.setCompanyId(obj.getCompany().getId());
					dto.setCompanyName(obj.getCompany().getName());
				}
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
			AuthenticatedUser user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getUserId());
	        Example<Vender> example = Example.of(filterBy);
			List<Vender> objs = venderService.findAll(example);
				
			// objs.forEach(d -> {
			// 	if(d!=null && d.getId()!=null) {
			// 		sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
			// 	}
			// });
		    // return sb.toString();

			sb.append("<option value=''>Nothing Selected</option>");
			objs.forEach(d -> {
				sb.append("<option value=" + d.getId() + ">" +d.getName() + "</option>");
				// sb.append("<option value=" + d.getId() + ">" +d.getIcode()+" ~ "+d.getIname() + "</option>");
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
				if(obj.getCompany() != null) {
					dto.setCompanyId(obj.getCompany().getId());
					dto.setCompanyName(obj.getCompany().getName());
				}
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
			AuthenticatedUser user = requestUtil.getCurrentUser();
			dto.setUserId(user.getUserId());
			obj.setUserId(user.getUserId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				obj.setUserId(user.getUserId());
				obj.setName(dto.getName());
				Example<Vender> example = Example.of(obj);
				if(venderService.exists(example))
					return new GenericResponse("FOUND", "Vender '" + dto.getName() + "' already exists.");
			}

			obj = modelMapper.map(dto, Vender.class);
			//if it is update
			if(!appUtil.isEmptyOrNull(dto.getId())) {
				Vender existing = venderService.findById(dto.getId()).orElse(null);
				if(existing != null) obj.setDated(existing.getDated());
			}else {
				obj.setDated(dated);
			}
			obj.setUpdated(dated);

			obj.setCompany(companyService.getReferenceById(dto.getCompanyId()));
			obj = venderService.save(obj);
			if(appUtil.isEmptyOrNull(obj)) {
				return new GenericResponse("FAILED", "Failed to save vender. Please try again.");
			}else {
				return new GenericResponse("SUCCESS", "Vender saved successfully.");
			}
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(this.getClass().getName()+" > addVender "+e.getCause());
			return new GenericResponse("ERROR", "An unexpected error occurred. Please contact support.");
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
