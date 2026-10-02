package com.myplus.business_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.PayableOutbox;

public interface PayableOutboxRepo extends JpaRepository<PayableOutbox, Long> {

    List<PayableOutbox> findTop100ByStatusOrderByIdAsc(String status);
}
