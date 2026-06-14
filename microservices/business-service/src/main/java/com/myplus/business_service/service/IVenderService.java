package com.myplus.business_service.service;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.business_service.entity.Vender;

public interface IVenderService extends org.springframework.data.jpa.repository.JpaRepository<com.myplus.business_service.entity.Vender, Long> {

	/** Tenant-scoped venders (own org + caller's pre-migration org-NULL rows). */
	List<Vender> findScoped(Long orgId, Long userId);

}
