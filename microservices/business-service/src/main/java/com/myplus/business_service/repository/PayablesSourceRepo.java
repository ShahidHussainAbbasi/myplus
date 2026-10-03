package com.myplus.business_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.PayablesSource;

/** FP-4b — the per-tenant payables read switch (V74). */
public interface PayablesSourceRepo extends JpaRepository<PayablesSource, Long> { }
