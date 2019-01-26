package com.service.education;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.persistence.Repo.education.StudentRepo;

public interface IStudentService extends StudentRepo{

	@Modifying
	@Query(value = "update school s set u.status = ? where u.id = ?", nativeQuery = true)
	int updateStatus(String status, String id);
}
