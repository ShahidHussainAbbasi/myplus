package com.myplus.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.myplus.auth.entity.SupportSession;

/** E5 — support sessions: the operator's open ones, and one tenant's whole history. */
public interface SupportSessionRepository extends JpaRepository<SupportSession, Long> {

    /**
     * The operator's currently-open sessions.
     *
     * <p>Plural on purpose: an operator handling two customers at once holds two, and collapsing them to one
     * would silently close the first when the second opened — losing the record of why it was ever open.
     */
    @Query("select s from SupportSession s where s.operatorUserId = :userId "
            + "and s.closedAt is null and s.expiresAt > :now order by s.id desc")
    List<SupportSession> findOpenForOperator(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** One tenant's history, newest first — what the customer's own Platform access card lists. */
    @Query("select s from SupportSession s where s.subjectOrgId = :orgId order by s.id desc")
    List<SupportSession> findBySubject(@Param("orgId") Long orgId, Pageable pageable);

    Optional<SupportSession> findByIdAndSubjectOrgId(Long id, Long subjectOrgId);
}
