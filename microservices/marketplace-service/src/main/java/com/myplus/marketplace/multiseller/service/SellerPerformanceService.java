package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.time.TenantClock;
import com.myplus.commerce.domain.Money;
import com.myplus.marketplace.multiseller.domain.SellerPerformance;
import com.myplus.marketplace.multiseller.dto.PerformanceDTOs;
import com.myplus.marketplace.multiseller.repository.MarketplaceReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;

import com.myplus.common.web.exception.ValidationException;
import lombok.RequiredArgsConstructor;

/**
 * MKT-2e — how each seller is doing: orders accepted, how fast, delivered on time, orders it did not fulfil, returns
 * it caused, and whether it is late paying for cash orders (slice doc {@code mkt-2e-merchant-performance.md}).
 *
 * <p>Read on demand from the orders placed in the window, never stored: one query over {@code mkt_seller_order}
 * (idx_mkt_so_created, V33) and one over returns. The counting is {@link SellerPerformance}.
 *
 * <p><b>Ranking.</b> The catalogue's last tie-break before the offer id is "acceptance history" (MKT-1a
 * {@code OfferRanker}); until now it was 0 for everyone. {@link #acceptanceRate} answers it from a map of each seller's
 * {@value #RANK_DAYS}-day acceptance rate, refreshed every 10 minutes and whenever the operator opens the 30-day
 * scorecard, so a catalogue request reads no orders. A seller without {@value SellerPerformance#MIN_ORDERS} decided
 * orders ranks as 1.0: a new seller is not pushed down for having no history.
 */
@Service
@RequiredArgsConstructor
public class SellerPerformanceService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerPerformanceService.class);

    /** The windows the screens offer. */
    public static final Set<Integer> WINDOWS = Set.of(7, 30, 90);
    public static final int DEFAULT_DAYS = 30;
    /** The window the ranking uses. */
    static final int RANK_DAYS = 30;

    private final MarketplaceSellerOrderRepository sellerOrders;
    private final MarketplaceReturnRepository returns;
    private final MarketplaceSellerAccountRepository sellerAccounts;
    private final MarketplaceSettlementEntryRepository entries;
    private final CodStandingService cod;
    private final SellerAccess access;

    private volatile Map<Long, Double> rankRates = Map.of();

    /** The operator's scorecard: every seller with an order in the window; flagged sellers first. */
    @Transactional(readOnly = true)
    public PerformanceDTOs.PerformanceView operator(Integer days) {
        access.assertOperator();
        int d = days(days);
        LocalDateTime now = LocalDateTime.now(), from = now.minusDays(d);
        Map<Long, List<SellerPerformance.Part>> bySeller = group(sellerOrders.performanceFacts(from));
        Map<Long, Long> faults = faultReturns(from);
        Map<Long, Boolean> overdue = codOverdue();
        List<PerformanceDTOs.SellerScore> rows = new ArrayList<>();
        Map<Long, Double> rates = new HashMap<>();
        for (Map.Entry<Long, List<SellerPerformance.Part>> e : bySeller.entrySet()) {
            SellerPerformance.Score s = SellerPerformance.score(e.getValue(), now);
            rows.add(view(e.getKey(), s, faults.getOrDefault(e.getKey(), 0L), overdue.getOrDefault(e.getKey(), false)));
            if (s.acceptanceRate() != null) rates.put(e.getKey(), s.acceptanceRate());
        }
        if (d == RANK_DAYS) rankRates = Map.copyOf(rates);      // the same figures the ranking uses, fresh
        rows.sort(Comparator.comparing((PerformanceDTOs.SellerScore r) -> r.flags().isEmpty())
                .thenComparing(r -> r.acceptanceRate() == null ? 2d : r.acceptanceRate())
                .thenComparing(r -> r.sellerName() == null ? "" : r.sellerName()));
        return new PerformanceDTOs.PerformanceView(d, from.toLocalDate(), SellerPerformance.MIN_ORDERS,
                SellerPerformance.LOW_ACCEPTANCE, SellerPerformance.LOW_ON_TIME, rows);
    }

    /** The seller's own scorecard: the same figures the operator sees for it, never another seller's. */
    @Transactional(readOnly = true)
    public PerformanceDTOs.PerformanceView mine(Integer days) {
        Long org = access.org();
        int d = days(days);
        LocalDateTime now = LocalDateTime.now(), from = now.minusDays(d);
        List<SellerPerformance.Part> parts = group(sellerOrders.performanceFactsOf(from, org)).getOrDefault(org, List.of());
        SellerPerformance.Score s = SellerPerformance.score(parts, now);
        boolean late = cod.standing(org).overdue();
        return new PerformanceDTOs.PerformanceView(d, from.toLocalDate(), SellerPerformance.MIN_ORDERS,
                SellerPerformance.LOW_ACCEPTANCE, SellerPerformance.LOW_ON_TIME,
                List.of(view(org, s, faultReturns(from).getOrDefault(org, 0L), late)));
    }

    /** The ranking's "acceptance history" for one seller: 1.0 when it has too few orders to say. */
    public double acceptanceRate(Long sellerOrg) {
        Double r = sellerOrg == null ? null : rankRates.get(sellerOrg);
        return r == null ? 1d : r;
    }

    @Scheduled(fixedDelayString = "${mkt.performance.refresh-ms:600000}", initialDelayString = "${mkt.performance.refresh-initial-ms:60000}")
    @Transactional(readOnly = true)
    public void refreshRanking() {
        try {
            LocalDateTime now = LocalDateTime.now();
            Map<Long, Double> rates = new HashMap<>();
            group(sellerOrders.performanceFacts(now.minusDays(RANK_DAYS))).forEach((org, parts) -> {
                Double r = SellerPerformance.score(parts, now).acceptanceRate();
                if (r != null) rates.put(org, r);
            });
            rankRates = Map.copyOf(rates);
        } catch (RuntimeException e) {
            LOG.warn("MKT seller performance: ranking rates not refreshed, keeping the last ({})", e.toString());
        }
    }

    static int days(Integer days) {
        if (days == null) return DEFAULT_DAYS;
        if (!WINDOWS.contains(days)) throw new ValidationException("Choose 7, 30 or 90 days.");
        return days;
    }

    static Map<Long, List<SellerPerformance.Part>> group(List<Object[]> rows) {
        Map<Long, List<SellerPerformance.Part>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            SellerPerformance.Part p = new SellerPerformance.Part((Long) r[0], (String) r[1], (LocalDateTime) r[2],
                    (LocalDateTime) r[3], (LocalDateTime) r[4], r[5] == null ? null : ((Number) r[5]).intValue(),
                    (String) r[6], (String) r[7]);
            out.computeIfAbsent(p.sellerOrgId(), k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    private Map<Long, Long> faultReturns(LocalDateTime from) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] r : returns.sellerFaultReturns(from)) out.put((Long) r[0], ((Number) r[1]).longValue());
        return out;
    }

    /** Late for cash orders (MKT-2d): only a seller whose balance is negative can be, so only those are walked. */
    private Map<Long, Boolean> codOverdue() {
        Map<Long, Boolean> out = new HashMap<>();
        for (Object[] r : entries.balances()) {
            BigDecimal bal = Money.scale(Money.nz((BigDecimal) r[1]));
            if (bal.signum() < 0) out.put((Long) r[0], cod.standing((Long) r[0], bal, TenantClock.today()).overdue());
        }
        return out;
    }

    private PerformanceDTOs.SellerScore view(Long org, SellerPerformance.Score s, long faults, boolean codLate) {
        return new PerformanceDTOs.SellerScore(org, sellerAccounts.findByOrganizationId(org).map(a -> a.getDisplayName()).orElse(null),
                s.accepted(), s.missed(), s.disputed(), s.excused(), s.acceptanceRate(), s.avgMinutesToAccept(), s.due(),
                s.onTime(), s.onTimeRate(), faults, codLate, s.flags(codLate));
    }
}
