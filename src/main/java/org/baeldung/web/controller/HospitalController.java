package org.baeldung.web.controller;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;

import org.baeldung.persistence.dao.HospitalRepository;
import org.baeldung.persistence.model.Hospital;
import org.baeldung.persistence.model.User;
import org.baeldung.security.ActiveUserStore;
import org.baeldung.service.IHospitalService;
import org.baeldung.web.dto.HospitalDto;
import org.baeldung.web.util.GenericResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HospitalController {

	 private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	    @Autowired
	    private MessageSource messages;
    @Autowired
    ActiveUserStore activeUserStore;

    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired
    IHospitalService hospitalService;

    @Autowired
    private AuthenticationManager authenticationManager;    
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
        
        final Hospital hospital = hospitalService.registerNewHospital(hospitalDto);
//        eventPublisher.publishEvent(new OnRegistrationCompleteEvent(hospital, request.getLocale(), getAppUrl(request)));
    	}catch(Exception e) {
    		e.printStackTrace();
    		return	new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),e.getCause().toString());
    	}
        return new GenericResponse("success");
    }
    
//    @RequestMapping(value = "/registerHospital", method = RequestMethod.POST)
//    public String registerHospital(final Locale locale, final Model model) {
//    	HospitalDto hospitalDto = (HospitalDto) model;
//    	hospitalService.registerNewHospital(hospitalDto);
//    	return "hospital";
//    }
    
    @RequestMapping(value = "/addHospital", method = RequestMethod.GET)
    public String addHospital(final Locale locale, final Model model) {
//        model.addAttribute("users", activeUserStore.getUsers());
        return "hospital";
    }

    private String getAppUrl(HttpServletRequest request) {
        return "http://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
    }    
}
