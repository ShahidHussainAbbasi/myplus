package org.baeldung.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.baeldung.persistence.dao.AppointmentRepository;
import org.baeldung.persistence.model.Appointment;
import org.baeldung.web.dto.AppointmentDTO;
import org.baeldung.web.util.AppUtil;
import org.baeldung.web.util.GenericResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

public class AppointmentValidater implements Validator {

	List<ObjectError> allErrors;
	private static String SUCCESS = "SUCCESS";
	private static String FAILURE = "FAILURE";

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Override
    public boolean supports(final Class<?> clazz) {
        return AppointmentDTO.class.isAssignableFrom(clazz);
    }

    
    public GenericResponse validate(final AppointmentDTO appointmentDTO) {
    	ObjectError objectError=null;
        //if already appointed
    	GenericResponse genericResponse = new GenericResponse();
    	allErrors = new ArrayList<>();
        Optional<Appointment> optionAppointment = appointmentRepository.isPatientAppointed(appointmentDTO.getDoctorId(),AppUtil.todayDateStr(),appointmentDTO.getMobile());
        if(optionAppointment.isPresent())
        	objectError = new ObjectError("Appointment", "Already appointed with your number "+appointmentDTO.getMobile());
        	
    	if(objectError==null)
    		return genericResponse;
    		
    	allErrors.add(objectError);
    	genericResponse = new GenericResponse(allErrors, "You can not request for the same appointment again");
    	genericResponse.setStatus(FAILURE);
    	return genericResponse;
    }


	@Override
	public void validate(Object target, Errors errors) {
		// TODO Auto-generated method stub
		
	}

}
