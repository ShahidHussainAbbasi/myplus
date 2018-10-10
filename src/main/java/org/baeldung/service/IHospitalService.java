package org.baeldung.service;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Optional;

import org.baeldung.persistence.model.Hospital;
import org.baeldung.persistence.model.PasswordResetToken;
import org.baeldung.persistence.model.User;
import org.baeldung.persistence.model.VerificationToken;
import org.baeldung.web.dto.HospitalDto;
import org.baeldung.web.dto.UserDto;
import org.baeldung.web.error.UserAlreadyExistException;

public interface IHospitalService {

	Hospital registerNewHospital(HospitalDto hospitalDto) throws UserAlreadyExistException;

    void saveHospital(Hospital hospital);

    void deleteHospital(Hospital hospital);

    Hospital findHospitalByEmail(String email);

    Optional<Hospital> getHospitalByID(long id);

    String generateQRUrl(Hospital hospital) throws UnsupportedEncodingException;

}
