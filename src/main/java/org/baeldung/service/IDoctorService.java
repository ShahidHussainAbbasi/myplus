package org.baeldung.service;

import java.util.Optional;

import org.baeldung.persistence.model.Doctor;
import org.baeldung.web.dto.DoctorDTO;

public interface IDoctorService {

	Doctor registerNewDoctor(DoctorDTO doctorDTO) throws Exception;

    void saveDoctor(DoctorDTO doctorDTO) throws Exception;

    void deleteDoctor(DoctorDTO doctorDTO) throws Exception;

    boolean isExist(String anme) throws Exception;

    Optional<DoctorDTO> fineByID(long id) throws Exception;


}
