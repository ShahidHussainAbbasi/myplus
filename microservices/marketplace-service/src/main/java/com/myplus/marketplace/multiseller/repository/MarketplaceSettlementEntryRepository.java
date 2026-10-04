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
}
