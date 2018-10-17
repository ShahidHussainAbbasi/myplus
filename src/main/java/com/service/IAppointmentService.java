package com.service;

import java.util.Optional;

import com.web.dto.AppointmentDTO;
import com.web.dto.DoctorDTO;
import com.web.util.GenericResponse;

public interface IAppointmentService {

	GenericResponse registerNewAppointment(AppointmentDTO appointmentDTO) throws Exception;

    void saveDoctor(DoctorDTO doctorDTO) throws Exception;

    void deleteDoctor(DoctorDTO doctorDTO) throws Exception;

    boolean isBlocked(String mobile) throws Exception;

    Optional<DoctorDTO> fineByID(long id) throws Exception;


}
