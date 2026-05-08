/**
 * 
 */
package com.persistence.Repo.business;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.business.CustomerHistory;

/**
 * @author sabbasi
 *
 */
public interface CustomerHistoryRepo extends JpaRepository<CustomerHistory, Long> {
	
}
