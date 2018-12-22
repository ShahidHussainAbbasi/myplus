package com.service.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import com.persistence.model.pharmacy.Item;

public interface IItemService extends JpaRepository<Item, Long>{


}
