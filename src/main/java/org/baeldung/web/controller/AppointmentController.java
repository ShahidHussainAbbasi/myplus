package org.baeldung.web.controller;

import java.util.List;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import org.baeldung.persistence.dao.DoctorRepository;
import org.baeldung.persistence.dao.HospitalRepository;
import org.baeldung.persistence.model.Doctor;
import org.baeldung.persistence.model.Hospital;
import org.baeldung.security.ActiveUserStore;
import org.baeldung.service.IAppointmentService;
import org.baeldung.web.dto.AppointmentDTO;
import org.baeldung.web.dto.DoctorDTO;
import org.baeldung.web.util.GenericResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

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
	public GenericResponse appointmentReq(final AppointmentDTO appointmentDTO, final HttpServletRequest request) {
		try {
			LOGGER.debug("Registering hospital account with information: {}", appointmentDTO);
		    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (!authenticationTrustResolver.isAnonymous(authentication)) 
				return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),"NotSupported");
			
			if (appointmentService.isBlocked(appointmentDTO.getMobile()))
				return new GenericResponse(messages.getMessage("message.hospital.exist", null, request.getLocale()),
						"HospitalAlreadyExist");

			final AppointmentDTO appointmentDTO2 = appointmentService.registerNewAppointment(appointmentDTO);
//        eventPublisher.publishEvent(new OnRegistrationCompleteEvent(hospital, request.getLocale(), getAppUrl(request)));
		} catch (Exception e) {
			e.printStackTrace();
			return new GenericResponse(messages.getMessage("message.userNotFound", null, request.getLocale()),
					e.getCause().toString());
		}
		return new GenericResponse("success");
	}

    @RequestMapping(value = "/appointment", method = RequestMethod.GET)
    public ModelAndView addHospital(final Locale locale, final Model model) {
    	List<Hospital> hospitals  = hospitalRepository.findAll();
    	AppointmentDTO appointmentDTO = new AppointmentDTO();
//    	for(Hospital hospital: hospitals) {
//    		doctorDTO.getHospitals().put(hospital.getHospitalId(), hospital.getName());
//    	}
    	List<Doctor> doctors  = doctorRepository.findAll();
//    	for(Doctor doctor: doctors) {
//    		doctorDTO.getdos().put(doctor.getDoctorId(), doctor.getName());
//    	}
    	model.addAttribute("hospitals", hospitals);
    	model.addAttribute("doctors", doctors);
		return new ModelAndView("appointment","appointmentDTO",appointmentDTO);
    }

}
