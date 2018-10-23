package com.web.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
import org.springframework.web.servlet.ModelAndView;

import com.persistence.dao.HospitalRepository;
import com.persistence.model.Doctor;
import com.persistence.model.Hospital;
import com.persistence.model.User;
import com.security.ActiveUserStore;
import com.service.IDoctorService;
import com.web.dto.DoctorDTO;
import com.web.util.GenericResponse;

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

	@RequestMapping(value = "/loadDoctorDetails", method = RequestMethod.GET)
	@ResponseBody
	public String loadDoctorDetails(@RequestParam Long doctorId) {
		Doctor doctor = new Doctor();
		StringBuffer sb = new StringBuffer();
		try {
			doctor = doctorService.fineByID(doctorId).get();
			sb.append("<p id='schedule'>Days From : "+doctor.getDayFrom()+" To "+doctor.getDayTo()+ " <br/>");
			sb.append("Time From : "+doctor.getTimeIn()+" To "+doctor.getTimeOut()+ " <br/>");
			sb.append("Specialist : "+doctor.getSpeciality()+ " </p>");
		}catch (Exception e) {
			e.printStackTrace();
			return "";
		}	
	    return sb.toString();
	}	

	@RequestMapping(value = "/loadDoctorsByHospital", method = RequestMethod.GET)
	@ResponseBody
	public String loadDoctorsByHospital(@RequestParam Long hospitalId) {
		List<Doctor> doctors = doctorService.findByHospitalId(hospitalId);
		StringBuffer sb = new StringBuffer();
		sb.append("<option value='-1'> Select Doctor </option>");
		doctors.forEach(d -> {
			if(d!=null)
				sb.append("<option value='"+d.getDoctorId()+"'>"+d.getName()+"</option>");
			
		});
	    return sb.toString();
	}	

	
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
    	model.addAttribute("days", Arrays.asList("All","Monday","Tuesday","Wednesday","Thursday","Fiday"));
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
