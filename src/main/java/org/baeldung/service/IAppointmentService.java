package org.baeldung.service;

import java.util.Optional;

import org.baeldung.web.dto.AppointmentDTO;
import org.baeldung.web.dto.DoctorDTO;
import org.baeldung.web.util.GenericResponse;

public interface IAppointmentService {

	GenericResponse registerNewAppointment(AppointmentDTO appointmentDTO) throws Exception;

    void saveDoctor(DoctorDTO doctorDTO) throws Exception;

    void deleteDoctor(DoctorDTO doctorDTO) throws Exception;

    boolean isBlocked(String mobile) throws Exception;

    Optional<DoctorDTO> fineByID(long id) throws Exception;


}
