package org.baeldung.service;

import java.util.Optional;

import org.baeldung.persistence.model.Appointment;
import org.baeldung.web.dto.AppointmentDTO;
import org.baeldung.web.dto.DoctorDTO;

public interface IAppointmentService {

	AppointmentDTO registerNewAppointment(AppointmentDTO appointmentDTO) throws Exception;

    void saveDoctor(DoctorDTO doctorDTO) throws Exception;

    void deleteDoctor(DoctorDTO doctorDTO) throws Exception;

    boolean isBlocked(String mobile) throws Exception;

    Optional<DoctorDTO> fineByID(long id) throws Exception;


}
