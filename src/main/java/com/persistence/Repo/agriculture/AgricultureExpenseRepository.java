/**
 * 
 */
package com.persistence.Repo.agriculture;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.persistence.model.agriculture.AgricultureExpense;

/**
 * @author sabbasi
 *
 */
@Repository
public interface AgricultureExpenseRepository extends JpaRepository<AgricultureExpense, Long>{
	
	
}
