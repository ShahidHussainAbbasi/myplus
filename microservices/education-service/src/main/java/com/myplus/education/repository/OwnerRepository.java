package com.myplus.education.repository;

import com.myplus.education.entity.Owner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OwnerRepository extends JpaRepository<Owner, Long> {
    Page<Owner> findByUserId(Long userId, Pageable pageable);
    List<Owner> findByUserId(Long userId);
    long countByUserId(Long userId);
}
