package com.myplus.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.BusinessType;

/** HC-1a — the business types (V35). */
public interface BusinessTypeRepository extends JpaRepository<BusinessType, String> {

    List<BusinessType> findAllByOrderByLineKeyAscSortOrderAsc();
}
