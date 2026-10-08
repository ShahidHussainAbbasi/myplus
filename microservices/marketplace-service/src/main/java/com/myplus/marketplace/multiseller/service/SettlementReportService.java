package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.domain.Money;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.LedgerEntryType;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-2f — the settlement report: each seller's ledger over a period, column by column (slice doc
 * {@code mkt-2f-settlement-reports.md}; source R20.3 "settlement reports", §15–16).
 *
 * <p>Read from the ledger only, the one place money is written (R15.6), so the report cannot disagree with the
 * statement. Every entry type lands in exactly one column ({@link #COLUMN}); a type with no column would make the
 * columns stop adding up, and {@code everyTypeHasAColumn} fails the build. {@code closing} is computed as opening plus
 * the columns, and is the seller's balance at the end of the period.
 */
@Service
@RequiredArgsConstructor
public class SettlementReportService {

    /** A longer period is refused: a year is the longest anyone reconciles at once. */
    static final int MAX_DAYS = 366;

    enum Col { SALES, COMMISSION, FEES_AND_TAX, RESERVE, REFUNDS, CORRECTIONS, COLLECTED, REMITTED, PAID_OUT }

    /** Every ledger type, in one column. */
    static final Map<LedgerEntryType, Col> COLUMN = new EnumMap<>(Map.ofEntries(
            Map.entry(LedgerEntryType.SALE, Col.SALES),
            Map.entry(LedgerEntryType.COMMISSION, Col.COMMISSION),
            Map.entry(LedgerEntryType.DELIVERY_FEE, Col.FEES_AND_TAX),
            Map.entry(LedgerEntryType.PROCESSING_FEE, Col.FEES_AND_TAX),
            Map.entry(LedgerEntryType.TAX, Col.FEES_AND_TAX),
            Map.entry(LedgerEntryType.RESERVE, Col.RESERVE),
            Map.entry(LedgerEntryType.RESERVE_RELEASE, Col.RESERVE),
            Map.entry(LedgerEntryType.REFUND, Col.REFUNDS),
            Map.entry(LedgerEntryType.REVERSAL, Col.REFUNDS),
            Map.entry(LedgerEntryType.ADJUSTMENT, Col.CORRECTIONS),
            Map.entry(LedgerEntryType.COLLECTED_BY_SELLER, Col.COLLECTED),
            Map.entry(LedgerEntryType.REMITTANCE, Col.REMITTED),
            Map.entry(LedgerEntryType.PAYOUT, Col.PAID_OUT)));

    private final MarketplaceSettlementEntryRepository entries;
    private final MarketplaceSellerAccountRepository sellerAccounts;
    private final SellerAccess access;

    /** The operator's report: every seller with a ledger row before the end of the period, and the totals. */
    @Transactional(readOnly = true)
    public SettlementDTOs.SettlementReport operator(LocalDate from, LocalDate to) {
        access.assertOperator();
        return report(from, to, null);
    }

    /** The seller's own row: the same figures the operator sees for it. */
    @Transactional(readOnly = true)
    public SettlementDTOs.SettlementReport mine(LocalDate from, LocalDate to) {
        return report(from, to, access.org());
    }

    private SettlementDTOs.SettlementReport report(LocalDate from, LocalDate to, Long only) {
        LocalDate today = TenantClock.today();
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) throw new ValidationException("The start of the period is after its end.");
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) throw new ValidationException("Choose a period of at most 366 days.");
        List<SettlementDTOs.ReportRow> rows = rows(entries.balancesBefore(startOf(f)),
                entries.periodTotals(startOf(f), startOf(t.plusDays(1))), only,
                org -> sellerAccounts.findByOrganizationId(org).map(a -> a.getDisplayName()).orElse(null));
        return new SettlementDTOs.SettlementReport(f, t, rows, total(rows));
    }

    /**
     * The first instant of the tenant's day, as the ledger stores it. {@code effective_at} is written by
     * {@code LocalDateTime.now()} under the UTC default zone ({@code UtcDefaultTimeZone}), while a day the operator picks is
     * a day in Pakistan: 1 October starts at 30 September 19:00 in the ledger. Without this, a sale at 01:00 on the 1st
     * would be reported in September.
     */
    static LocalDateTime startOf(LocalDate day) {
        return day.atStartOfDay(TenantClock.zone()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Pure: [orgId, balance] openings and [orgId, type, debit, credit, count] period rows → one row per seller. */
    static List<SettlementDTOs.ReportRow> rows(List<Object[]> openings, List<Object[]> period, Long only,
            Function<Long, String> names) {
        Map<Long, BigDecimal> opening = new HashMap<>();
        for (Object[] r : openings) opening.put((Long) r[0], Money.nz((BigDecimal) r[1]));
        Map<Long, Map<Col, BigDecimal>> cols = new HashMap<>();
        Map<Long, Integer> lines = new HashMap<>();
        for (Object[] r : period) {
            Long org = (Long) r[0];
            LedgerEntryType type = LedgerEntryType.valueOf((String) r[1]);
            BigDecimal net = Money.nz((BigDecimal) r[3]).subtract(Money.nz((BigDecimal) r[2]));   // seller's side
            cols.computeIfAbsent(org, k -> new EnumMap<>(Col.class)).merge(COLUMN.get(type), net, BigDecimal::add);
            if (type == LedgerEntryType.SALE) lines.merge(org, ((Number) r[4]).intValue(), Integer::sum);
        }
        TreeSet<Long> orgs = new TreeSet<>(opening.keySet());
        orgs.addAll(cols.keySet());
        List<SettlementDTOs.ReportRow> out = new ArrayList<>();
        for (Long org : orgs) {
            if (only != null && !only.equals(org)) continue;
            Map<Col, BigDecimal> c = cols.getOrDefault(org, Map.of());
            BigDecimal open = Money.scale(opening.getOrDefault(org, BigDecimal.ZERO));
            BigDecimal closing = open;
            for (BigDecimal v : c.values()) closing = closing.add(v);
            out.add(new SettlementDTOs.ReportRow(org, names.apply(org), open, col(c, Col.SALES), col(c, Col.COMMISSION),
                    col(c, Col.FEES_AND_TAX), col(c, Col.RESERVE), col(c, Col.REFUNDS), col(c, Col.CORRECTIONS),
                    col(c, Col.COLLECTED), col(c, Col.REMITTED), col(c, Col.PAID_OUT), Money.scale(closing),
                    lines.getOrDefault(org, 0)));
        }
        if (only != null && out.isEmpty()) {           // a seller with no ledger yet still gets its row: all zero
            BigDecimal z = Money.ZERO;
            out.add(new SettlementDTOs.ReportRow(only, names.apply(only), z, z, z, z, z, z, z, z, z, z, z, 0));
        }
        out.sort(Comparator.comparing((SettlementDTOs.ReportRow r) -> r.sellerName() == null ? "" : r.sellerName())
                .thenComparing(SettlementDTOs.ReportRow::organizationId));
        return out;
    }

    static SettlementDTOs.ReportRow total(List<SettlementDTOs.ReportRow> rows) {
        BigDecimal[] s = new BigDecimal[11];
        java.util.Arrays.fill(s, Money.ZERO);
        int lines = 0;
        for (SettlementDTOs.ReportRow r : rows) {
            BigDecimal[] v = { r.opening(), r.sales(), r.commission(), r.feesAndTax(), r.reserve(), r.refunds(),
                    r.corrections(), r.collectedBySeller(), r.remitted(), r.paidOut(), r.closing() };
            for (int i = 0; i < v.length; i++) s[i] = s[i].add(v[i]);
            lines += r.lines();
        }
        return new SettlementDTOs.ReportRow(null, null, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], lines);
    }

    private static BigDecimal col(Map<Col, BigDecimal> c, Col k) {
        return Money.scale(c.getOrDefault(k, BigDecimal.ZERO));
    }
}
