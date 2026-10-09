package com.myplus.marketplace.multiseller.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.myplus.marketplace.multiseller.entity.MarketplaceSettlementEntry;

/**
 * MKT-1g — the settlement ledger. Deliberately a bare {@link Repository}, not a JpaRepository: it can INSERT and READ,
 * and offers no delete. An entry is never changed after insert (its columns are {@code updatable = false} as well).
 */
public interface MarketplaceSettlementEntryRepository extends Repository<MarketplaceSettlementEntry, Long> {

    MarketplaceSettlementEntry save(MarketplaceSettlementEntry e);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /** idx_mkt_entry_account — the seller's statement, newest first. */
    Page<MarketplaceSettlementEntry> findByOrganizationIdOrderByIdDesc(Long organizationId, Pageable page);

    /** idx_mkt_entry_line — the rows one line settled into (for a statement line's figures). */
    List<MarketplaceSettlementEntry> findByOrderLineIdIn(Collection<Long> lineIds);

    /** The balance: credits minus debits. Zero for a seller with no rows. */
    @Query("select coalesce(sum(e.creditAmount), 0) - coalesce(sum(e.debitAmount), 0) from MarketplaceSettlementEntry e "
            + "where e.organizationId = :org")
    BigDecimal balance(@Param("org") Long organizationId);

    /** Every seller with a ledger, and its balance: [orgId, balance]. Bounded by the number of sellers. */
    @Query("select e.organizationId, coalesce(sum(e.creditAmount), 0) - coalesce(sum(e.debitAmount), 0) "
            + "from MarketplaceSettlementEntry e group by e.organizationId order by e.organizationId")
    List<Object[]> balances();

    /** MKT-2d — per seller, the debits and credits of one entry type: [orgId, debit, credit]. Bounded by the sellers. */
    @Query("select e.organizationId, coalesce(sum(e.debitAmount), 0), coalesce(sum(e.creditAmount), 0) "
            + "from MarketplaceSettlementEntry e where e.entryType = :type group by e.organizationId")
    List<Object[]> totalsOfType(@Param("type") String entryType);

    /**
     * MKT-2f — the report: per seller and entry type, debits, credits and rows with {@code effectiveAt} in
     * [from, to) (idx_mkt_entry_effective, V34). Rows: [orgId, type, debit, credit, count].
     */
    @Query("select e.organizationId, e.entryType, coalesce(sum(e.debitAmount), 0), coalesce(sum(e.creditAmount), 0), count(e) "
            + "from MarketplaceSettlementEntry e where e.effectiveAt >= :from and e.effectiveAt < :to "
            + "group by e.organizationId, e.entryType")
    List<Object[]> periodTotals(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    /** MKT-2f — each seller's balance before {@code before}: the report's opening. Rows: [orgId, balance]. */
    @Query("select e.organizationId, coalesce(sum(e.creditAmount), 0) - coalesce(sum(e.debitAmount), 0) "
            + "from MarketplaceSettlementEntry e where e.effectiveAt < :before group by e.organizationId")
    List<Object[]> balancesBefore(@Param("before") java.time.LocalDateTime before);
}
