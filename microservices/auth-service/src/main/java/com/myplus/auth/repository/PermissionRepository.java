package com.myplus.auth.repository;

import com.myplus.auth.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** The catalog. Read-only in practice — rows arrive by migration, never from a request. */
public interface PermissionRepository extends JpaRepository<Permission, String> {

    /** Every permission, in the order the matrix draws them. */
    List<Permission> findAllByOrderBySortOrderAsc();
}
