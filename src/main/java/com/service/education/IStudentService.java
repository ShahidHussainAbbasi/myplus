package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.Student;

public interface IStudentService extends JpaRepository<Student, Long>{


}
