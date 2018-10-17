/**
 * 
 */
package org.baeldung.persistence.dao;

import org.baeldung.persistence.model.Appointment;
import org.baeldung.web.dto.AppointmentDashboardDTO;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

/**
 * @author sabbasi
 *
 */
@Repository
public interface AppointmentDashboardRepository extends PagingAndSortingRepository<Appointment,Long> {
	
}
