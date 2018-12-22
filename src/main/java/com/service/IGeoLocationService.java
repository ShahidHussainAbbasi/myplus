package com.service;

import java.util.List;
import java.util.Optional;

import com.persistence.Repo.GeoLocationRepo;
import com.persistence.model.Geolocation;
import com.persistence.model.User;

public interface IGeoLocationService extends GeoLocationRepo{

    Optional<User> getUserByID(long id);

	List<Geolocation> loadStatesByCountry(String countryCode);
	
	List<Geolocation> loadCitiesByState(String state);
}
