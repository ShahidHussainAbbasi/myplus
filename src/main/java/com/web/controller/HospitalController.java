package com.web.controller;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.persistence.model.Geolocation;
import com.persistence.model.Hospital;
import com.security.ActiveUserStore;
import com.service.IGeoLocationService;
import com.service.IHospitalService;
import com.web.dto.HospitalDto;
import com.web.util.AppUtil;
import com.web.util.GenericResponse;

@Controller
public class HospitalController {

	 private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	    @Autowired
	    private MessageSource messages;
    @Autowired
    ActiveUserStore activeUserStore;

    @Autowired
    IHospitalService hospitalService;

/*    @Autowired
    AppUtil appUtil;
*/    
	@Autowired
	IGeoLocationService geoLocationService;
    
    @RequestMapping(value = "/registerHospital", method = RequestMethod.POST)
    @ResponseBody
    public GenericResponse registerHospital(final HospitalDto hospitalDto, final HttpServletRequest request) {
    	try {
        LOGGER.debug("Registering hospital account with information: {}", hospitalDto);
        UsernamePasswordAuthenticationToken authToken =  (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        if (!authToken.isAuthenticated()) 
        	new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()), "UserNotFound");

        if(hospitalService.findByName(hospitalDto.getName()))
        	return new GenericResponse(messages.getMessage("message.hospital.exist", null, request.getLocale()), "HospitalAlreadyExist");
        
	        hospitalService.registerNewHospital(hospitalDto);
	        return new GenericResponse("Hospital registered successfully");
    	}catch(Exception e) {
    		e.printStackTrace();
    		return	new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),e.getCause().toString());
    	}
    }
    
//    @RequestMapping(value = "/registerHospital", method = RequestMethod.POST)
//    public String registerHospital(final Locale locale, final Model model) {
//    	HospitalDto hospitalDto = (HospitalDto) model;
//    	hospitalService.registerNewHospital(hospitalDto);
//    	return "hospital";
//    }
    
    @RequestMapping(value = "/addHospital", method = RequestMethod.GET)
    public String addHospital(final Locale locale, final Model model) {
/*    	Map<String,String> coutries = new HashMap<>();
    	AppUtil.countryMapcountries().forEach(c ->{
    		String city=c.getCity()!=null?c.getCity():"-";
    		String state=c.getState()!=null?c.getState():"-";
    		String country=c.getCountry()!=null?c.getCountry():"-";
    		coutries.put(c.getCountryCode(),c.getCountry()!=null?c.getCountry():"-");
    	});
*/        
    	model.addAttribute("countries", AppUtil.countryMap);
        return "hospital";
    }

	@RequestMapping(value = "/loadStatesByCountry", method = RequestMethod.GET)
	@ResponseBody
	public String loadStatesByCountry(@RequestParam String countryCode) {
		List<Geolocation> states = geoLocationService.loadStatesByCountry(countryCode);
		Set<String> items = new HashSet<>();  
		states.forEach(d -> {
			if(d!=null)
				items.add(d.getState());
			
		});
		StringBuffer sb = new StringBuffer();
		sb.append("<option value='-1'> Select State </option>");
		items.forEach(d -> {
			if(d!=null)
				sb.append("<option value="+d+">"+((d!=null && d!="")?d:"-")+"</option>");
			
		});
	    return sb.toString();
	}	
    
	@RequestMapping(value = "/loadCitiesByState", method = RequestMethod.GET)
	@ResponseBody
	public String loadCitiesByState(@RequestParam String state) {
		List<Geolocation> cities = geoLocationService.loadCitiesByState(state);
		StringBuffer sb = new StringBuffer();
		sb.append("<option value='-1'> Select City </option>");
		cities.forEach(d -> {
			if(d!=null)
				sb.append("<option value='"+d.getId()+"'>"+(d.getCity()!=null?d.getCity():"-")+"</option>");
			
		});
	    return sb.toString();
	}	

	private String getAppUrl(HttpServletRequest request) {
        return "http://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
    }    
}
