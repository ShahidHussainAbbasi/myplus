package com.myplus.education.repository;

import com.myplus.education.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Page<Student> findByUserId(Long userId, Pageable pageable);
    List<Student> findByUserId(Long userId);
    Page<Student> findBySchoolId(Long schoolId, Pageable pageable);
    Page<Student> findByGradeId(Long gradeId, Pageable pageable);
    long countByUserId(Long userId);

    /** Tenant-scoped read: active org rows + caller's not-yet-migrated (NULL-org) rows. See 01-school. */
    @Query("select s from Student s where s.organizationId = :orgId "
            + "or (s.organizationId is null and s.userId = :userId)")
    List<Student> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    // P4 — branch (school) scoped read, the education twin of business's findScopedByStores. Rows with no
    // school are legacy and stay visible (they drain as they are re-saved), exactly as with store_id.
    //
    // NOTE the deliberate difference from POS: a teacher sees their BRANCH's students, not merely the ones
    // they personally created. A roster is shared by the staff of a school — own-only (the cashier/till rule)
    // would hide a colleague's students from the teacher who has to teach them. Branch is the boundary here.
    @Query("select s from Student s where s.organizationId = :orgId "
            + "and (s.schoolId in :schoolIds or s.schoolId is null)")
    List<Student> findScopedBySchools(@Param("orgId") Long orgId, @Param("schoolIds") java.util.Collection<Long> schoolIds);
}
