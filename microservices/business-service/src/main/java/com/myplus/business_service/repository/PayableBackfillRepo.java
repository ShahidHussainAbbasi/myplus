package com.myplus.business_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.PayableBackfill;

public interface PayableBackfillRepo extends JpaRepository<PayableBackfill, Long> {
}
