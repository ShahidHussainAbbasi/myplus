package com.myplus.welfare.service;

import java.util.List;

import org.springframework.data.domain.Example;

import com.myplus.welfare.entity.Donation;

public interface IDonationService {
    List<Donation> findAll();
    List<Donation> findAll(Example<Donation> example);
    /** Tenant-scoped donations (own org + caller's pre-migration org-NULL rows). */
    List<Donation> findScoped(Long orgId, Long userId);
    Donation getOne(Long id);
    Donation save(Donation donation);
    void deleteById(Long id);
}
