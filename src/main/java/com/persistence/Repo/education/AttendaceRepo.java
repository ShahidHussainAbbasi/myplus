/**
 * 
 */
package com.persistence.Repo.education;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

import com.persistence.model.education.Attendance;


/**
 * @author sabbasi
 *
 */
public interface AttendaceRepo extends JpaRepository<Attendance, Long>,QueryByExampleExecutor<Attendance> {

}
