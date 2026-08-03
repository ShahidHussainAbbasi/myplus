package com.myplus.education.repository;

import com.myplus.education.entity.Homework;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface HomeworkRepository extends JpaRepository<Homework, Long> {

    /** Everything set for the tenant, soonest due first — the list screen. */
    @Query("select h from Homework h where h.organizationId = :orgId "
            + "or (h.organizationId is null and h.userId = :userId) order by h.dueOn desc, h.id desc")
    List<Homework> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** One subject's homework — the filter a teacher actually uses. */
    @Query("select h from Homework h where (h.organizationId = :orgId "
            + "or (h.organizationId is null and h.userId = :userId)) "
            + "and h.subjectId = :subjectId order by h.dueOn desc, h.id desc")
    List<Homework> findBySubjectScoped(@Param("subjectId") Long subjectId,
                                       @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Several subjects at once — the class view, without a query per subject. */
    @Query("select h from Homework h where (h.organizationId = :orgId "
            + "or (h.organizationId is null and h.userId = :userId)) "
            + "and h.subjectId in :subjectIds order by h.dueOn desc, h.id desc")
    List<Homework> findBySubjectsScoped(@Param("subjectIds") Collection<Long> subjectIds,
                                        @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE task by a client-supplied id within the caller's tenant. */
    @Query("select h from Homework h where h.id = :id and (h.organizationId = :orgId "
            + "or (h.organizationId is null and h.userId = :userId))")
    Optional<Homework> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                      @Param("userId") Long userId);
}
