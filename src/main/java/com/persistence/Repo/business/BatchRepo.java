/**
 * 
 */
package com.persistence.Repo.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.business.Stock;

/**
 * @author sabbasi
 *
 */
public interface BatchRepo extends JpaRepository<Stock, Long>,QueryByExampleExecutor<Stock> {
	
}
