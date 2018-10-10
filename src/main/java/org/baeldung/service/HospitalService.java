package org.baeldung.service;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.Optional;

import javax.transaction.Transactional;

import org.baeldung.persistence.dao.HospitalRepository;
import org.baeldung.persistence.dao.PasswordResetTokenRepository;
import org.baeldung.persistence.dao.RoleRepository;
import org.baeldung.persistence.dao.VerificationTokenRepository;
import org.baeldung.persistence.model.Hospital;
import org.baeldung.web.dto.HospitalDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Transactional
public class HospitalService implements IHospitalService {

    @Autowired
    private HospitalRepository repository;

    @Autowired
    private VerificationTokenRepository tokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRegistry sessionRegistry;
    
    @Autowired
    UserService userService;

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";

    // API

    @Override
    public Hospital registerNewHospital(final HospitalDto hospitalDto) {
//        if (emailExist(accountDto.getEmail())) {
//            throw new UserAlreadyExistException("There is an account with that email adress: " + accountDto.getEmail());
//        }
        final Hospital hospital = new Hospital();
        
        hospital.setUserId(BigInteger.valueOf(userService.getUserFromSessionRegistry().getId()));
        hospital.setDoctors(hospitalDto.getDoctors());

        hospital.setCountry(hospitalDto.getCountry());
        hospital.setCity(hospitalDto.getCity());
        hospital.setDatetime(hospitalDto.getDatetime());
        hospital.setEmail(hospitalDto.getEmail());
        hospital.setLogoUrl(hospitalDto.getLogoUrl());
        hospital.setName(hospitalDto.getName());
        hospital.setPhone(hospitalDto.getPhone());
        hospital.setState(hospitalDto.getState());
        hospital.setZip(hospitalDto.getZip());
        hospital.setAppointmentOfferType(hospitalDto.getAppointmentOfferType());
        
//
//        user.setFirstName(accountDto.getFirstName());
//        user.setLastName(accountDto.getLastName());
//        user.setPassword(passwordEncoder.encode(accountDto.getPassword()));
//        user.setEmail(accountDto.getEmail());
//        user.setUsing2FA(accountDto.isUsing2FA());
//        user.setRoles(Arrays.asList(roleRepository.findByName("ROLE_USER")));
        return repository.save(hospital);
    }

	@Override
	public void saveHospital(Hospital hospital) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteHospital(Hospital hospital) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Hospital findHospitalByEmail(String email) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Optional<Hospital> getHospitalByID(long id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String generateQRUrl(Hospital hospital) throws UnsupportedEncodingException {
		// TODO Auto-generated method stub
		return null;
	}


}