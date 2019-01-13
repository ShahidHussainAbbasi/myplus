package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.School;

public interface ISchoolService extends JpaRepository<School, Long>{


}
