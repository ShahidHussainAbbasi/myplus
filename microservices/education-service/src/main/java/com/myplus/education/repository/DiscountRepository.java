package com.myplus.education.repository;

import com.myplus.education.entity.Discount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscountRepository extends JpaRepository<Discount, Long> {
    Page<Discount> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);
}
