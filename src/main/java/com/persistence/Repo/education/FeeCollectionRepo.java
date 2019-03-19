/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.FeeCollection;


/**
 * @author sabbasi
 *
 */
public interface FeeCollectionRepo extends JpaRepository<FeeCollection, Long>,QueryByExampleExecutor<FeeCollection> {

}
