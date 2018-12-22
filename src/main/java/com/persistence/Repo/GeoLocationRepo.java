package com.persistence.Repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.persistence.model.Geolocation;

public interface GeoLocationRepo extends JpaRepository<Geolocation, Long> {
   
	Geolocation findByCountryCode(String countryCode);

	@Query(value="SELECT * FROM geolocation g ORDER BY country ASC",nativeQuery=true)
	List<Geolocation> loadCountries();

/*	@Query(value="SELECT DISTINCT country,state FROM geolocation ORDER BY state ASC",nativeQuery=true)
	List<Geolocation> loadStates(String);
*/
	@Override
    void delete(Geolocation user);

}
