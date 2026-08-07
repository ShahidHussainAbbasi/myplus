package com.myplus.education.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.education.entity.MeetingEvent;
import com.myplus.education.entity.MeetingEventStatus;

@Repository
public interface MeetingEventRepository extends JpaRepository<MeetingEvent, Long> {

    /** The school's own list, newest first — every evening, open or closed. */
    @Query("select m from MeetingEvent m where (m.organizationId = :orgId "
            + "or (m.organizationId is null and m.userId = :userId)) order by m.eventDate desc, m.id desc")
    List<MeetingEvent> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * What a family may book right now.
     *
     * <p><b>No {@code userId} NULL-fallback</b>, the same deliberate strictness as every other portal read
     * (3.1, 3.3, 3.5): that fallback exists so staff still see rows created before org-scoping landed, and
     * an external principal has no such history to be shown.
     */
    @Query("select m from MeetingEvent m where m.organizationId = :orgId and m.status = :status "
            + "order by m.eventDate desc, m.id desc")
    List<MeetingEvent> findOpenForPortal(@Param("orgId") Long orgId, @Param("status") MeetingEventStatus status);

    /** Anti-IDOR: resolve ONE evening by a client-supplied id, within the caller's tenant. */
    @Query("select m from MeetingEvent m where m.id = :id and (m.organizationId = :orgId "
            + "or (m.organizationId is null and m.userId = :userId))")
    Optional<MeetingEvent> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                          @Param("userId") Long userId);
}
