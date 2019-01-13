package com.service.education;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.education.Guardian;

public interface IGuardianService extends JpaRepository<Guardian, Long>{


}
