/**
 * 
 */
package com.myplus.business_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.business_service.entity.Stock;

/**
 * @author sabbasi
 *
 */
public interface BatchRepo extends JpaRepository<Stock, Long>,QueryByExampleExecutor<Stock> {
	
}
