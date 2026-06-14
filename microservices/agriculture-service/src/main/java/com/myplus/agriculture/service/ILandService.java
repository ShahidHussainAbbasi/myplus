package com.myplus.agriculture.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Example;

import com.myplus.agriculture.entity.Land;

public interface ILandService {
    List<Land> findAll();
    List<Land> findAll(Example<Land> example);
    /** Tenant-scoped lands (own org + caller's pre-migration org-NULL rows). */
    List<Land> findScoped(Long orgId, Long userId);
    boolean exists(Example<Land> example);
    Optional<Land> findById(Long id);
    Land save(Land land);
    void deleteById(Long id);
}
