/**
 * 
 */
package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.myplus.business_service.entity.Customer;

/**
 * @author sabbasi
 *
 */
public interface CustomerRepo extends JpaRepository<Customer, Long>,QueryByExampleExecutor<Customer> {
	

   List<Customer> findByUserId(Long userId);
}
