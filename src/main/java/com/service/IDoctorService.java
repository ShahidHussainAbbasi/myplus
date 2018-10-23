package com.service;

import java.util.List;
import java.util.Optional;

import com.persistence.model.Doctor;
import com.web.dto.DoctorDTO;

public interface IDoctorService {

	Doctor registerNewDoctor(DoctorDTO doctorDTO) throws Exception;

    void saveDoctor(DoctorDTO doctorDTO) throws Exception;

    void deleteDoctor(DoctorDTO doctorDTO) throws Exception;

    boolean isExist(String mobile) throws Exception;

    Optional<Doctor> fineByID(Long id) throws Exception;

	List<Doctor> findByHospitalId(Long hospitalId);


}
