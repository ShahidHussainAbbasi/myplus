package com.myplus.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.myplus.auth.entity.EndpointPermission;

/** RW-0c — the monolith's endpoint rules (V26), most specific first. */
public interface EndpointPermissionRepository extends JpaRepository<EndpointPermission, Long> {

    List<EndpointPermission> findAllByOrderBySortOrderAsc();
}
