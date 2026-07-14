package com.myplus.auth.repository;

import com.myplus.auth.entity.UserLocationAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Access grants for the multi-location model. Reads are keyed by user + org so the JWT builder can resolve
 * the caller's accessible locations for the active tenant.
 */
public interface UserLocationAccessRepository extends JpaRepository<UserLocationAccess, Long> {

    List<UserLocationAccess> findByUserIdAndOrganizationIdAndStatus(Long userId, Long organizationId, String status);

    List<UserLocationAccess> findByOrganizationIdAndModuleAndLocationId(Long organizationId, String module, Long locationId);
}
