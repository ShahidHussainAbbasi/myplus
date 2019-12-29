/**
 * 
 */
package com.persistence.Repo.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.business.Item;

/**
 * @author sabbasi
 *
 */
public interface ItemRepo extends JpaRepository<Item, Long>,QueryByExampleExecutor<Item> {
	
}
