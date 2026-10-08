package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.domain.Money;
import com.myplus.common.security.time.TenantClock;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceSettlementEntry;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2d — what a seller owes MaxTheService for cash orders, and since when (slice doc
 * {@code mkt-2d-cod-reconciliation.md}; source R-MKT-2).
 *
 * <p>A cash order leaves the customer's money with the seller's rider: the ledger books the sale, the commission and
 * COLLECTED_BY_SELLER, so the seller's balance goes negative by what it owes. A card sale or a correction can bring it
 * back; a REMITTANCE (the seller paid) does. So "owed" is simply the negative balance, never a second sum that could
 * disagree with the statement.
 *
 * <p>"Owed since" is the day the balance last went below zero and stayed there: walking the rows newest first, the
 * oldest row after which the balance never came back to zero. A seller that pays a part keeps its date: the debt is
 * smaller, not newer. The rows one order line settled into (SALE, COMMISSION, COLLECTED_BY_SELLER) are ONE step: between
 * its SALE credit and its COLLECTED debit the balance is briefly positive, which is not the seller having paid. Separate from {@link MarketplaceSettlementService} so checkout can ask without the settlement
 * service's other edges.
 */
@Service
@RequiredArgsConstructor
public class CodStandingService {

    /** Rows read per page while looking for the day the debt began; 50 pages = 5,000 rows, far beyond any seller today. */
    static final int PAGE = 100;
    static final int MAX_PAGES = 50;

    private final MarketplaceSettlementEntryRepository entries;
    private final MarketplaceSettingsService settings;

    /** The seller's standing today. */
    @Transactional(readOnly = true)
    public SettlementDTOs.CodStanding standing(Long org) {
        BigDecimal balance = Money.scale(Money.nz(entries.balance(org)));
        return standing(org, balance, TenantClock.today());
    }

    /** The same, when the caller already read the balance. */
    @Transactional(readOnly = true)
    public SettlementDTOs.CodStanding standing(Long org, BigDecimal balance, LocalDate today) {
        if (balance.signum() >= 0) return new SettlementDTOs.CodStanding(Money.ZERO, null, null, false, false);
        LocalDate since = owedSince(org, balance);
        LocalDate payBy = since == null ? null : since.plusDays(settings.codRemitDays());
        boolean overdue = payBy != null && today.isAfter(payBy);
        return new SettlementDTOs.CodStanding(balance.negate(), since, payBy, overdue, overdue && settings.codStopWhenOverdue());
    }

    /** Checkout's question: is cash on delivery stopped for this seller? Only when the operator switched it on. */
    @Transactional(readOnly = true)
    public boolean codStopped(Long org) {
        if (!settings.codStopWhenOverdue()) return false;    // off by default: no ledger read on every checkout
        return standing(org).codStopped();
    }

    private LocalDate owedSince(Long org, BigDecimal balance) {
        Walk w = new Walk(null, balance, false, null);
        for (int p = 0; p < MAX_PAGES; p++) {
            Page<MarketplaceSettlementEntry> page = entries.findByOrganizationIdOrderByIdDesc(org, PageRequest.of(p, PAGE));
            w = walk(w, page.getContent());
            if (w.done() || !page.hasNext()) return w.since();
        }
        return w.since();
    }

    /**
     * Where the walk stands: the date found so far, the balance before the last row read, whether it is finished, and
     * the order line of the last row read (so a line split across two pages is still one step).
     */
    record Walk(LocalDate since, BigDecimal balanceBefore, boolean done, Long lastLine) {
    }

    /**
     * One page of rows, newest first, continuing {@code from}. Stops at the first step before which the balance was
     * zero or more: the step after it began the debt.
     */
    static Walk walk(Walk from, List<MarketplaceSettlementEntry> newestFirst) {
        BigDecimal running = from.balanceBefore();
        LocalDate since = from.since();
        Long last = from.lastLine();
        for (MarketplaceSettlementEntry e : newestFirst) {
            boolean newStep = last == null || !last.equals(e.getOrderLineId());
            if (newStep && running.signum() >= 0) return new Walk(since, running, true, last);
            if (e.getEffectiveAt() != null) since = e.getEffectiveAt().toLocalDate();
            running = running.subtract(Money.nz(e.getCreditAmount())).add(Money.nz(e.getDebitAmount()));
            last = e.getOrderLineId();
        }
        return new Walk(since, running, false, last);
    }
}
