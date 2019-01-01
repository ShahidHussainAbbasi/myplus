/**
 * 
 */
package com.persistence.Repo.abbasiWelfare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.abbasiWelfare.Donation;

/**
 * @author sabbasi
 *
 */
@Repository
public interface DonationRepository extends JpaRepository<Donation, Long>{
	
	
}
