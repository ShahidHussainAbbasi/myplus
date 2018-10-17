package org.baeldung.service;

import java.io.UnsupportedEncodingException;
import java.util.Optional;

import org.baeldung.persistence.model.Hospital;
import org.baeldung.web.dto.HospitalDto;

public interface IHospitalService {

	Hospital registerNewHospital(HospitalDto hospitalDto) throws Exception;

    void saveHospital(Hospital hospital);

    void deleteHospital(Hospital hospital);

    boolean findByName(String anme);

    Optional<Hospital> getHospitalByID(long id);

    String generateQRUrl(Hospital hospital) throws UnsupportedEncodingException;

}
