package com.service.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.business.Company;

public interface ICompanyService extends JpaRepository<Company, Long>{


}
