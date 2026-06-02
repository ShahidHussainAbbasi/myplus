package com.myplus.education.repository;

import com.myplus.education.entity.AlertChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertChannelRepository extends JpaRepository<AlertChannel, Long> {
    Page<AlertChannel> findByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);
}
