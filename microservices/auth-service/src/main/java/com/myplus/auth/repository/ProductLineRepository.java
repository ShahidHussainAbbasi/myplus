package com.myplus.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.ProductLine;

/** HC-1a — the product lines (V35). */
public interface ProductLineRepository extends JpaRepository<ProductLine, String> {

    List<ProductLine> findAllByOrderBySortOrderAsc();
}
