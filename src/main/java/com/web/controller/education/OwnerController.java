package com.web.controller.education;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
import com.persistence.model.education.Owner;
import com.service.education.IOwnerService;
import com.web.dto.education.OwnerDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;

@Controller
public class OwnerController {

	@Autowired
	private MessageSource messages;

	@Autowired
	IOwnerService ownerService;

	@Autowired
	AppUtil appUtil;
	
	@Autowired
	RequestUtil requestUtil;

	ModelMapper modelMapper = new ModelMapper();
	
	@RequestMapping(value = "/getUserOwner", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserOwner(final HttpServletRequest request) {
		try {
			Owner filterBy = new Owner();
//			User user = (User)(RequestUtil.userProperties.get("user"));
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Owner> example = Example.of(filterBy);
			List<Owner> objs = ownerService.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()));

			List<OwnerDTO> dtos=new ArrayList<OwnerDTO>(); 
			objs.forEach(obj ->{
				OwnerDTO dto = modelMapper.map(obj, OwnerDTO.class);
				dto.setDatedStr(appUtil.getDateStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateStr(obj.getUpdated()));
				dtos.add(dto);
			});
			return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),dtos);
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/getUserOwners", method = RequestMethod.GET)
	@ResponseBody
	public String getUserOwners(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Owner filterBy = new Owner();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Owner> example = Example.of(filterBy);
			List<Owner> objs = ownerService.findAll(example);
			
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null) {
					sb.append("<option value="+d.getId()+">"+d.getName()+"</option>");
				}
			});
		    return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllOwner", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllOwner(final HttpServletRequest request) {
		try {
			List<Owner> owners = ownerService.findAll();
			if(appUtil.isEmptyOrNull(owners)){
				return new GenericResponse("NOT_FOUND",messages.getMessage("message.userNotFound", null, request.getLocale()),owners);
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()),owners);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/addOwner", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addOwner(@Validated final OwnerDTO dto, final HttpServletRequest request) {
		try {
			Owner obj= new Owner();
			LocalDateTime dated = LocalDateTime.now();
			User user = requestUtil.getCurrentUser();
			dto.setUserId(user.getId());
			if(appUtil.isEmptyOrNull(dto.getId())){
				Example<Owner> example = Example.of(obj);
				obj.setUserId(user.getId());
				obj.setName(dto.getName());
				if(ownerService.exists(example))
					return new GenericResponse("FOUND",messages.getMessage("The Owner "+dto.getName()+" already exist", null, request.getLocale()));
			}
			
			obj = modelMapper.map(dto, Owner.class);
			obj.setDated(dated);
			obj.setUpdated(dated);
			Owner schoolOwnerTemp = ownerService.save(obj);
			if(appUtil.isEmptyOrNull(schoolOwnerTemp)) {
				return new GenericResponse("FAILED",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}else {
				return new GenericResponse("SUCCESS",messages.getMessage("message.userNotFound", null, request.getLocale()));
			}
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse("ERROR",messages.getMessage(e.getCause().getCause().toString(), null, request.getLocale()),
					e.getCause().toString());
		}
	}
	
	@RequestMapping(value = "/deleteOwner", method = RequestMethod.POST)
	@ResponseBody
	public boolean deleteOwner( HttpServletRequest req, HttpServletResponse resp ){
		try {
		String ids = req.getParameter("checked");
			if(!StringUtils.isEmpty(ids)) {
				String idList[] = ids.split(",");
				for(String id:idList){
					ownerService.deleteById(Long.valueOf(id));//.updateStatus("Inactive",id);//deleteById(Long.valueOf(id));
				}
				return true;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}else {
				return false;// new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"SUCCESS");
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;//new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
		}
	}
}
