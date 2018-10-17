package com.service;

import java.util.Optional;

import com.persistence.model.Doctor;
import com.web.dto.DoctorDTO;

public interface IDoctorService {

	Doctor registerNewDoctor(DoctorDTO doctorDTO) throws Exception;

    void saveDoctor(DoctorDTO doctorDTO) throws Exception;

    void deleteDoctor(DoctorDTO doctorDTO) throws Exception;

    boolean isExist(String anme) throws Exception;

    Optional<DoctorDTO> fineByID(long id) throws Exception;


}
