/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Vehicle;

/**
 * @author sabbasi
 *
 */
public interface VehicleRepo extends JpaRepository<Vehicle, Long>,QueryByExampleExecutor<Vehicle> {
	
	@Modifying
	@Query(value = "update vehicle o set o.status = ? where o.vehicle_id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
