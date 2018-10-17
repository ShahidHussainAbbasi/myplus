/**
 * 
 */
package com.persistence.dao;

import com.persistence.model.Appointment;
import com.web.dto.AppointmentDashboardDTO;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

/**
 * @author sabbasi
 *
 */
@Repository
public interface AppointmentDashboardRepository extends PagingAndSortingRepository<Appointment,Long> {
	
}
