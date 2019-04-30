package com.service;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.persistence.Repo.HospitalRepository;
import com.persistence.model.Hospital;
import com.web.dto.HospitalDto;
import com.web.util.AppUtil;

@Service
@Transactional
public class HospitalService implements IHospitalService {

    @Autowired
    private HospitalRepository repository;
    
    @Autowired
    UserService userService;
    
    @Autowired
    private AppUtil appUtil;  
    

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    public static String QR_PREFIX = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=";
    public static String APP_NAME = "SpringRegistration";

    // API

    @Override
    public Hospital registerNewHospital(final HospitalDto hospitalDto) throws Exception{

        final Hospital hospital = new Hospital();
        
        hospital.setUserId(BigInteger.valueOf(Long.valueOf(userService.getUsersIdFromSessionRegistry().get(0))));
        hospital.setDoctors(hospitalDto.getDoctors());

        hospital.setName(hospitalDto.getName());
        hospital.setPhone(hospitalDto.getPhone());
        hospital.setEmail(hospitalDto.getEmail());
        hospital.setLogoUrl(hospitalDto.getLogoUrl());
        hospital.setGeoId(BigInteger.valueOf(Long.valueOf(hospitalDto.getGeoId())));
/*        hospital.setState(hospitalDto.getState());
        hospital.setCity(hospitalDto.getCity());
        hospital.setZip(hospitalDto.getZip());
*///       	hospital.setAppointmentOfferType(hospitalDto.getAppointmentOfferType()+"/"+hospitalDto.getHours());
//        hospital.setAppointmentOfferValue(hospitalDto.getAppointmentOfferValue());
        
        hospital.setDatetime(appUtil.todayDateStr());
        
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
	public boolean findByName(String name) {
		return repository.findByName(name).isPresent();
	}
//		Hospital filterBy = new Hospital();
//		filterBy.setName(name);
//        Example<Hospital> example = Example.of(filterBy);
//		return repository.exists(example);

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