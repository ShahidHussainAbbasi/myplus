package com.myplus.welfare.service;

import java.util.List;

import org.springframework.data.domain.Example;

import com.myplus.welfare.entity.Donator;

public interface IDonatorService {
    List<Donator> findAll(Example<Donator> example);
    /** Tenant-scoped donators (own org + caller's pre-migration org-NULL rows). */
    List<Donator> findScoped(Long orgId, Long userId);
    boolean exists(Example<Donator> example);
    Donator getOne(Long id);
    Donator save(Donator donator);
    void deleteById(Long id);
}
