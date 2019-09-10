/**
 * 
 */
package com.persistence.Repo.agriculture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.agriculture.AgricultureIncome;

/**
 * @author sabbasi
 *
 */
@Repository
public interface AgricultureIncomeRepository extends JpaRepository<AgricultureIncome, Long>{
	
	
}
