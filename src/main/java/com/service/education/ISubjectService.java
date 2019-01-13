package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.Subject;

public interface ISubjectService extends JpaRepository<Subject, Long>{


}
