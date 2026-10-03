package com.myplus.finance.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.finance.entity.SetOff;

public interface SetOffRepository extends JpaRepository<SetOff, Long> {

    @Query("SELECT s FROM SetOff s WHERE s.organizationId = :orgId AND s.idempotencyKey = :key")
    Optional<SetOff> findByKey(@Param("orgId") Long orgId, @Param("key") String key);
}
