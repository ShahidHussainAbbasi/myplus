package org.baeldung.web.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;

import org.baeldung.persistence.dao.HospitalRepository;
import org.baeldung.persistence.model.Doctor;
import org.baeldung.persistence.model.Hospital;
import org.baeldung.persistence.model.User;
import org.baeldung.security.ActiveUserStore;
import org.baeldung.service.IDoctorService;
import org.baeldung.service.IHospitalService;
import org.baeldung.web.dto.DoctorDTO;
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
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class DoctorController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;
	@Autowired
	ActiveUserStore activeUserStore;

	@Autowired
	IDoctorService doctorService;

	@Autowired
	HospitalRepository hospitalRepository;

	@RequestMapping(value = "/registerDoctor", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse registerDoctor(final DoctorDTO doctorDto, final HttpServletRequest request) {
		try {
			LOGGER.debug("Registering hospital account with information: {}", doctorDto);
			UsernamePasswordAuthenticationToken authToken = (UsernamePasswordAuthenticationToken) SecurityContextHolder
					.getContext().getAuthentication();
			if (!authToken.isAuthenticated())
				new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
						"UserNotFound");

			if (doctorService.isExist(doctorDto.getMobile()))
				return new GenericResponse(messages.getMessage("message.hospital.exist", null, request.getLocale()),
						"HospitalAlreadyExist");

			final Doctor doctor = doctorService.registerNewDoctor(doctorDto);
//        eventPublisher.publishEvent(new OnRegistrationCompleteEvent(hospital, request.getLocale(), getAppUrl(request)));
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
		return new GenericResponse("success");
	}

    @RequestMapping(value = "/addDoctor", method = RequestMethod.GET)
    public ModelAndView addHospital(final Locale locale, final Model model) {
    	List<Hospital> hospitals  = hospitalRepository.findAll();
    	DoctorDTO doctorDTO = new DoctorDTO();
    	for(Hospital hospital: hospitals) {
    		doctorDTO.getHospitals().put(hospital.getHospitalId(), hospital.getName());
    	}
    	model.addAttribute("hospitals", doctorDTO.getHospitals());
		return new ModelAndView("doctor","doctorDTO",doctorDTO);
//        return "doctor";
    }

	private String getPrincipal(){
		String userName = null;
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
	
		if (principal instanceof User) {
			userName = ((User)principal).getFirstName() +" "+((User)principal).getLastName();
		} else {
			userName = principal.toString();
		}
		return userName;
	}
	private String getRole(){
		String role = null;
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
	
		if (principal instanceof User) {
				role = ((org.springframework.security.core.userdetails.User) principal).getAuthorities().iterator().next().getAuthority().toString();
		} else {
			role = principal.toString();
		}
		return role;
	}	

}
