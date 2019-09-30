/**
 * 
 */
package com.web.controller.agriculture;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.User;
import com.persistence.model.agriculture.Land;
import com.service.agriculture.LandService;
import com.web.dto.agriculture.LandDTO;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;
import com.web.util.RequestUtil;


/**
 * @author Shahid
 *
 */

//@RequestMapping("/agricultureIncome")
@Controller
public class LandController {

    @Autowired
	private MessageSource messages;    
	@Autowired
	LandService service;
	@Autowired
	RequestUtil requestUtil;
	
	@Autowired
	AppUtil appUtil;
	
	private ModelMapper modelMapper = new ModelMapper();

	@RequestMapping(value = "/addLand", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse addLand(final LandDTO dto, final HttpServletRequest request){
		try {
//			dto.setDatedStr(appUtil.todayDateStr());
			Land e = modelMapper.map(dto, Land.class);
			e.setUserId(requestUtil.getCurrentUser().getId());
			e.setDated(appUtil.getDateTime(dto.getDatedStr()));
			e.setUpdated(appUtil.getDateTime(dto.getUpdatedStr()));
			if(service.save(e).getId()>0)
				return new GenericResponse("Land added successfully");
			else
				return new GenericResponse("Sorry, Your land not added");
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.NOT_FOUND,messages.getMessage("Sorry, Your land not added", null, request.getLocale()),dto);
		}
	}
	
	@RequestMapping(value = "/getUserLand", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getUserLand(final HttpServletRequest request) {
		LandDTO dto = null;
		try {
			List<LandDTO> dtos = new ArrayList<>();
			Land agricultureIncome = new Land(requestUtil.getCurrentUser().getId());
			Example<Land> example = Example.of(agricultureIncome);
			List<Land> objs = service.findAll(example);
			if(appUtil.isEmptyOrNull(objs))
				return new GenericResponse(appUtil.NOT_FOUND,messages.getMessage("message.no.data.found", null, request.getLocale()),objs);
			for(Land obj: objs) {
				dto = new LandDTO();
				dto  = modelMapper.map(obj, LandDTO.class);
				dto.setDatedStr(appUtil.getDateTimeStr(obj.getDated()));
				dto.setUpdatedStr(appUtil.getDateTimeStr(obj.getUpdated()));
				dtos.add(dto);
			}
			return new GenericResponse("SUCCESS",dtos);
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.ERROR,messages.getMessage("message.system_error"+" : "+e.getCause().toString(), null, request.getLocale()),dto);
		}
	}
	
	@RequestMapping(value = "/getUserLands", method = RequestMethod.GET)
	@ResponseBody
	public String getUserLands(final HttpServletRequest request) {
		StringBuffer sb = new StringBuffer();
		try {
			Land filterBy = new Land();
			User user = requestUtil.getCurrentUser();
			filterBy.setUserId(user.getId());
	        Example<Land> example = Example.of(filterBy);
			List<Land> objs = service.findAll(example);
			if(appUtil.isEmptyOrNull(objs)) {
				sb.append("<option value=''> No Data </option>");
			}else {
				sb.append("<option value=''> Nothing Selected </option>");
			}
			objs.forEach(d -> {
				if(d!=null && d.getId()!=null)
					sb.append("<option value=" +d.getId() + ">" +  d.getLandName()+"- ("+d.getTotalLandUnit()+" "+d.getLandUnit()+")" + "</option>");
			});
		    return sb.toString();
		} catch (Exception e) {
//			e.printStackTrace();
			appUtil.le(this.getClass() ,e);
		}
	    return sb.toString();
	}

	@RequestMapping(value = "/getAllLand", method = RequestMethod.GET)
	@ResponseBody
	public GenericResponse getAllLand(final HttpServletRequest request) {
		try {
			List<Land> objs = service.findAll();
			if(appUtil.isEmptyOrNull(objs)){
				return new GenericResponse("NOT_FOUND");
			}else {
				return new GenericResponse("SUCCESS",objs);
			}
		} catch (Exception e) {
//			e.printStackTrace();
			appUtil.le(this.getClass() ,e);
			return new GenericResponse("ERROR",e.getCause().toString());
		}
	}

	@RequestMapping(value = "/deleteLand", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse deleteLand( HttpServletRequest request){
		try {
		String ids = request.getParameter("checked");
			if(StringUtils.isEmpty(ids)) 
				return new GenericResponse(appUtil.SUCCESS,messages.getMessage("message.invalid.input", null, request.getLocale()));
				
			String idList[] = ids.split(",");
			for(String id:idList){
				service.deleteById(Long.valueOf(id));
			}
			return new GenericResponse(appUtil.SUCCESS,messages.getMessage("message.delete.success", null, request.getLocale()));
		} catch (Exception e) {
			appUtil.le(this.getClass(), e);
			return new GenericResponse(appUtil.ERROR,messages.getMessage("message.system_error"+" : "+e.getCause().toString(), null, request.getLocale()));
		}
	}

}
