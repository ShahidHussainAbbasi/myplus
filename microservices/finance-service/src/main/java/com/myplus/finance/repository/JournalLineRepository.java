package com.myplus.finance.repository;

import com.myplus.finance.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** F3 (GL): journal-line aggregation for the trial balance + per-account ledger detail. Org-scoped via the entry. */
@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    /** Trial balance rows as {@code [accountId, Σdebit, Σcredit]} for entries dated on/before {@code asOf}. */
    @Query("SELECT jl.accountId, COALESCE(SUM(jl.debit),0), COALESCE(SUM(jl.credit),0) FROM JournalLine jl "
            + "WHERE jl.entry.organizationId = :orgId AND jl.entry.entryDate <= :asOf GROUP BY jl.accountId")
    List<Object[]> trialBalance(@Param("orgId") Long orgId, @Param("asOf") LocalDate asOf);

    /** One account's lines oldest-first (entry fetched) for the GL detail with a running balance. */
    @Query("SELECT jl FROM JournalLine jl JOIN FETCH jl.entry e WHERE jl.accountId = :accountId "
            + "AND e.organizationId = :orgId ORDER BY e.entryDate ASC, jl.id ASC")
    List<JournalLine> ledgerForAccount(@Param("accountId") Long accountId, @Param("orgId") Long orgId);
}
