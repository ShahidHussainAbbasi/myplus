package com.service.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.pharmacy.Company;

public interface ICompanyService extends JpaRepository<Company, Long>{


}
