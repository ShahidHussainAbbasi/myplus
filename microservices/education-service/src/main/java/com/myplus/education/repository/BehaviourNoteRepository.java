package com.myplus.education.repository;

import com.myplus.education.entity.BehaviourNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BehaviourNoteRepository extends JpaRepository<BehaviourNote, Long> {

    /**
     * One student's history, newest first — INCLUDING superseded notes.
     *
     * Superseded rows are returned deliberately: the trail is the point (D3), and hiding a corrected note
     * would reproduce the very problem immutability exists to prevent. The caller marks them, never drops
     * them.
     */
    @Query("select n from BehaviourNote n where (n.organizationId = :orgId "
            + "or (n.organizationId is null and n.userId = :userId)) "
            + "and n.studentEnrollNo = :enrollNo order by n.occurredOn desc, n.id desc")
    List<BehaviourNote> findByStudentScoped(@Param("enrollNo") String enrollNo,
                                            @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** The school-wide recent view. Bounded by the caller, which is what keeps this read cheap. */
    @Query("select n from BehaviourNote n where n.organizationId = :orgId "
            + "or (n.organizationId is null and n.userId = :userId) "
            + "order by n.occurredOn desc, n.id desc")
    List<BehaviourNote> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Notes for a SET of students in one query — the class view, never a query per child. */
    @Query("select n from BehaviourNote n where (n.organizationId = :orgId "
            + "or (n.organizationId is null and n.userId = :userId)) "
            + "and n.studentEnrollNo in :enrollNos order by n.occurredOn desc, n.id desc")
    List<BehaviourNote> findByStudentsScoped(@Param("enrollNos") Collection<String> enrollNos,
                                             @Param("orgId") Long orgId, @Param("userId") Long userId);

    /** Anti-IDOR: resolve ONE note by a client-supplied id within the caller's tenant. */
    @Query("select n from BehaviourNote n where n.id = :id and (n.organizationId = :orgId "
            + "or (n.organizationId is null and n.userId = :userId))")
    Optional<BehaviourNote> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                           @Param("userId") Long userId);
}
