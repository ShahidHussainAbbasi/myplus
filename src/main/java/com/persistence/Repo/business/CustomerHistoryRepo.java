package com.persistence.Repo.business;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.persistence.model.business.CustomerHistory;

public interface CustomerHistoryRepo extends JpaRepository<CustomerHistory, Long> {

    @Query("SELECT ch FROM CustomerHistory ch LEFT JOIN FETCH ch.customer WHERE ch.userId = :userId AND ch.dated >= :sd AND ch.dated <= :ed")
    List<CustomerHistory> findByUserIdAndDateRange(
        @Param("userId") Long userId,
        @Param("sd") LocalDateTime sd,
        @Param("ed") LocalDateTime ed
    );
}
