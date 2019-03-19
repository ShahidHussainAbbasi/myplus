package com.service.education;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.persistence.Repo.education.FeeCollectionRepo;
import com.persistence.model.education.FeeCollection;

public interface IFeeCollectionService extends FeeCollectionRepo{

	@Modifying
	@Query(value = "Select * from student s where s.enrollNo = ? or s.name = ? and user_id = ?", nativeQuery = true)
	FeeCollection findStudent(String enrollNo, String name,Long userId);

}
