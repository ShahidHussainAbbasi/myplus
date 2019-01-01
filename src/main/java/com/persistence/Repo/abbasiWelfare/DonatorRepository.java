/**
 * 
 */
package com.persistence.Repo.abbasiWelfare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.abbasiWelfare.Donation;
import com.persistence.model.abbasiWelfare.Donator;

/**
 * @author sabbasi
 *
 */
@Repository
public interface DonatorRepository extends JpaRepository<Donator, Long>{
	
	
}
