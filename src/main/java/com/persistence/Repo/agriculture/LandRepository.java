/**
 * 
 */
package com.persistence.Repo.agriculture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.agriculture.Land;

/**
 * @author sabbasi
 *
 */
@Repository
public interface LandRepository extends JpaRepository<Land, Long>{
	
	
}
