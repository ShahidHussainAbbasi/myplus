package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.SchoolOwner;

public interface ISchoolOwnerService extends JpaRepository<SchoolOwner, Long>{


}
