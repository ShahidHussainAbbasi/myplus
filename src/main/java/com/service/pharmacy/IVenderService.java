package com.service.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.business.Vender;

public interface IVenderService extends JpaRepository<Vender, Long>{


}
