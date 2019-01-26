package com.service.business;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.business.Item;

public interface IUnitService extends JpaRepository<Item, Long>{


}
