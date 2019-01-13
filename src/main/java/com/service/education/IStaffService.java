package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.Staff;

public interface IStaffService extends JpaRepository<Staff, Long>{


}
