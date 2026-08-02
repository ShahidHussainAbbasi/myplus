package com.myplus.education.repository;

import com.myplus.education.entity.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

    /** The school's leave types, in display order. */
    @Query("select t from LeaveType t where t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId) order by t.sequence, t.name")
    List<LeaveType> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * Duplicate check as an indexed EXISTS, never a full-table scan (finding D's lesson).
     * Case-insensitivity comes from the column COLLATION, not lower() — standard D3c: wrapping the column
     * in a function would look careful and defeat the index.
     */
    @Query("select case when count(t) > 0 then true else false end from LeaveType t "
            + "where (t.organizationId = :orgId or (t.organizationId is null and t.userId = :userId)) "
            + "and t.name = :name")
    boolean existsByNameScoped(@Param("name") String name,
                               @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE type by a client-supplied id within the caller's tenant. */
    @Query("select t from LeaveType t where t.id = :id and (t.organizationId = :orgId "
            + "or (t.organizationId is null and t.userId = :userId))")
    Optional<LeaveType> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                       @Param("userId") Long userId);
}
