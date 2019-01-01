package com.service.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.business.Item;

public interface IItemService extends JpaRepository<Item, Long>{


}
