package com.service;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.persistence.Repo.GeoLocationRepo;
import com.persistence.model.Doctor;
import com.persistence.model.Geolocation;
import com.persistence.model.Hospital;
import com.persistence.model.User;

@Service
@Transactional
public class GeoLocationService implements IGeoLocationService {

    @Autowired
    private GeoLocationRepo geoLocationRepo;

    // API

	@Override
	public Geolocation findByCountryCode(String countryCode) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Geolocation> loadCountries() {
		return geoLocationRepo.loadCountries();
	}

	@Override
	public void delete(Geolocation user) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public List<Geolocation> findAll() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Geolocation> findAll(Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Geolocation> findAllById(Iterable<Long> ids) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Geolocation> List<S> saveAll(Iterable<S> entities) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <S extends Geolocation> S saveAndFlush(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void deleteInBatch(Iterable<Geolocation> entities) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteAllInBatch() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Geolocation getOne(Long id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Geolocation> List<S> findAll(Example<S> example) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Geolocation> List<S> findAll(Example<S> example, Sort sort) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Page<Geolocation> findAll(Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Geolocation> S save(S entity) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Optional<Geolocation> findById(Long id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean existsById(Long id) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public long count() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void deleteById(Long id) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteAll(Iterable<? extends Geolocation> entities) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deleteAll() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public <S extends Geolocation> Optional<S> findOne(Example<S> example) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Geolocation> Page<S> findAll(Example<S> example, Pageable pageable) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public <S extends Geolocation> long count(Example<S> example) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public <S extends Geolocation> boolean exists(Example<S> example) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Optional<User> getUserByID(long id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Geolocation> loadStatesByCountry(String countryCode) {
		Geolocation geoLocation = new Geolocation();
		geoLocation.setCountryCode(countryCode);
		return geoLocationRepo.findAll(Example.of(geoLocation));
	}

	@Override
	public List<Geolocation> loadCitiesByState(String state) {
		Geolocation geoLocation = new Geolocation();
		geoLocation.setState(state);
		return geoLocationRepo.findAll(Example.of(geoLocation));
	}
}