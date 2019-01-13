package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.Grade;

public interface IGradeService extends JpaRepository<Grade, Long>{


}
