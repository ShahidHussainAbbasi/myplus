package com.web.controller;

import java.util.List;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import com.persistence.Repo.DoctorRepository;
import com.persistence.Repo.HospitalRepository;
import com.persistence.model.Hospital;
import com.security.ActiveUserStore;
import com.service.IAppointmentService;
import com.web.dto.AppointmentDTO;
import com.web.util.GenericResponse;

@Controller
public class AppointmentController {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
	@Autowired
	private MessageSource messages;
	@Autowired
	ActiveUserStore activeUserStore;

	@Autowired
	IAppointmentService appointmentService;

	@Autowired
	HospitalRepository hospitalRepository;
	
	@Autowired
	DoctorRepository doctorRepository;
	
	@Autowired
	AuthenticationTrustResolver authenticationTrustResolver;
	
	@RequestMapping(value = "/appointmentReq", method = RequestMethod.POST)
	@ResponseBody
	public GenericResponse appointmentReq(@Validated final AppointmentDTO appointmentDTO, final HttpServletRequest request) {
		try {
			
/*			String ipAddress = request.getHeader("X-FORWARDED-FOR");
			String ip = request.getRemoteAddr();
			LOGGER.debug("ipAddress: "+ipAddress+" ip: "+ip);		
					
*/			LOGGER.debug("Registering hospital account with information: {}", appointmentDTO);
//			Principal principal = request.getUserPrincipal();
		    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (!authenticationTrustResolver.isAnonymous(authentication)) 
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NotSupported");
			
			if (appointmentService.isBlocked(appointmentDTO.getMobile()))
				return new GenericResponse(messages.getMessage("Hospital"+"already.exist", null, request.getLocale()),
						"HospitalAlreadyExist");

			GenericResponse genericResponse =  appointmentService.registerNewAppointment(appointmentDTO);
			if(appointmentDTO.getAppntmntNo()!=null)
				genericResponse.setMessage("Dear "+appointmentDTO.getName()+" Your appointment number "+appointmentDTO.getAppntmntNo()+" has been registered for "+appointmentDTO.getMobile());
			return genericResponse;
//        eventPublisher.publishEvent(new OnRegistrationCompleteEvent(hospital, request.getLocale(), getAppUrl(request)));
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
//		return new GenericResponse("success");
	}

    @RequestMapping(value = "/appointment", method = RequestMethod.GET)
    public ModelAndView appointment(final Locale locale, final Model model) {
    	List<Hospital> hospitals  = hospitalRepository.findAll();
//    	AppointmentDTO appointmentDTO = new AppointmentDTO();
//    	for(Hospital hospital: hospitals) {
//    		doctorDTO.getHospitals().put(hospital.getHospitalId(), hospital.getName());
//    	}
//    	List<Doctor> doctors  = doctorRepository.findAll();
//    	for(Doctor doctor: doctors) {
//    		doctorDTO.getdos().put(doctor.getDoctorId(), doctor.getName());
//    	}
    	model.addAttribute("hospitals", hospitals);
//    	model.addAttribute("doctors", doctors);
		return new ModelAndView("appointment");
    }

    
}
