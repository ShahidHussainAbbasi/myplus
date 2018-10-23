/**
 * 
 */
package com.persistence.dao;

import java.util.List;

import org.springframework.data.domain.Page;
//import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties.Pageable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.persistence.model.Appointment;
import com.persistence.model.Hospital;

/**
 * @author sabbasi
 *
 */
@Repository
public interface AppointmentDashboardRepository extends PagingAndSortingRepository<Appointment,Long>, CrudRepository<Appointment, Long> {
	
	@Query( "select t from Appointment t where t.hospital in :hospital" )
	List<Appointment> findByHospitalIds(@Param("hospital") List<Hospital> hospital);
	
	@Query( "select t from Appointment t where t.hospital in :hospital" )
	Page<Appointment> findByHospitalIds2(@Param("hospital") List<Hospital> hospital,Pageable pageRequest);
	
	
}
