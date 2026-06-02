package com.myplus.business_service.service;

import com.myplus.business_service.entity.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IItemTypeService extends JpaRepository<ItemType, Long> {

}
