package com.myplus.education.repository;

import com.myplus.education.entity.ReportCardLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Lines are reached only through their card, which is already tenant-scoped — so these finders take a
 * card id rather than repeating the org predicate. Every caller resolves the card with
 * {@code findByIdScoped} first; a line finder that could be called with a bare id would be an IDOR
 * waiting to be wired up.
 */
@Repository
public interface ReportCardLineRepository extends JpaRepository<ReportCardLine, Long> {

    /** In issued order (D1) — a reopened card must read exactly as it was printed. */
    List<ReportCardLine> findByReportCardIdOrderBySequenceAsc(Long reportCardId);

    /** Batch load for a whole class or transcript — one query, not one per card (D8). */
    @Query("select l from ReportCardLine l where l.reportCardId in :cardIds order by l.reportCardId, l.sequence")
    List<ReportCardLine> findByCardIds(@Param("cardIds") List<Long> cardIds);
}
