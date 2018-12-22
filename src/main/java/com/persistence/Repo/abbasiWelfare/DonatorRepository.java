/**
 * 
 */
package com.persistence.Repo.abbasiWelfare;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.abbasiWelfare.Donator;

/**
 * @author sabbasi
 *
 */
@Repository
public interface DonatorRepository extends PagingAndSortingRepository<Donator,Long>, CrudRepository<Donator, Long> {
	
	
}
