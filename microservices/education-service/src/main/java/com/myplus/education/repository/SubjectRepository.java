package com.myplus.education.repository;

import com.myplus.education.entity.Subject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long> {
    Page<Subject> findByUserId(Long userId, Pageable pageable);
    List<Subject> findByUserId(Long userId);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select s from Subject s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    List<Subject> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
