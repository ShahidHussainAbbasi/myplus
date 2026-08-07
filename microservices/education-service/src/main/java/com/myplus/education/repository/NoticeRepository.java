package com.myplus.education.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.myplus.education.entity.Notice;
import com.myplus.education.entity.NoticeStatus;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /** The school's own list — drafts included, newest first. Staff surface only. */
    @Query("select n from Notice n where (n.organizationId = :orgId "
            + "or (n.organizationId is null and n.userId = :userId)) "
            + "order by n.dated desc")
    List<Notice> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId);

    /**
     * THE portal read: published notices for this tenant, newest first.
     *
     * <p><b>No {@code userId} NULL-fallback</b>, unlike its staff sibling. That fallback lets a staff member
     * see rows they created before org-scoping landed; an external principal has no such history, and
     * widening the predicate would widen their reach. The same deliberate strictness as
     * {@code findByGuardianScoped} (3.1) and {@code findByIdForPortal} (3.3).
     *
     * <p>The audience filter is applied in {@code NoticeAudienceResolver}, not here: it depends on WHO is
     * asking, and keeping it in one pure, tested place beats spreading it across query variants.
     */
    @Query("select n from Notice n where n.organizationId = :orgId and n.status = :status "
            + "order by n.publishedOn desc, n.id desc")
    List<Notice> findPublishedForPortal(@Param("orgId") Long orgId, @Param("status") NoticeStatus status);

    /** Anti-IDOR: resolve ONE notice by a client-supplied id, within the caller's tenant. */
    @Query("select n from Notice n where n.id = :id and (n.organizationId = :orgId "
            + "or (n.organizationId is null and n.userId = :userId))")
    Optional<Notice> findByIdScoped(@Param("id") Long id, @Param("orgId") Long orgId,
                                    @Param("userId") Long userId);
}
