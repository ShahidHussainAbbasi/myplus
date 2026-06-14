package com.myplus.education.repository;

import com.myplus.education.entity.Staff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffRepository extends JpaRepository<Staff, Long> {
    Page<Staff> findByUserId(Long userId, Pageable pageable);
    List<Staff> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select s from Staff s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    List<Staff> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
