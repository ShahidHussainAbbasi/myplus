package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.myplus.commerce.contracts.dto.PostingEventRequest;
import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.BusinessDayCalendar;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Settlement;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplacePayout;
import com.myplus.marketplace.multiseller.entity.MarketplaceReturn;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceSettlementEntry;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePayoutRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;

/**
 * MKT-1g — settlement through the REAL service, the tables in memory, the edges (finance outbox, audit, settings,
 * numbers) mocked. The money assertions are on the ledger rows and the journal the operator's books receive.
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceSettlementServiceTest {

    static final long SELLER = 7L, OPERATOR_ORG = 1L, ALICE = 501L, BOB = 502L;
    /** A Friday, so T+1 lands on the following Monday. */
    static final LocalDateTime FRIDAY = LocalDateTime.of(2026, 10, 2, 15, 0);

    @Mock MarketplaceOrderLineRepository lines;
    @Mock MarketplaceSellerOrderRepository sellerOrders;
    @Mock MarketplaceOrderRepository orders;
    @Mock MarketplaceReturnRepository returns;
    @Mock MarketplaceSettlementEntryRepository entries;
    @Mock MarketplacePayoutRepository payouts;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceGlOutboxService gl;
    @Mock MarketplaceAuditService audit;
    @Mock DocumentNumberService numbers;
    @Mock SellerAccess access;
    @Mock PlatformTransactionManager txManager;
    @Mock SettlementCalendarService calendar;
    MarketplaceSettlementService svc;
    final java.util.Set<LocalDate> holidays = new java.util.HashSet<>();
    CodStandingService cod;

    final Map<Long, MarketplaceOrderLine> lineTable = new HashMap<>();
    final Map<Long, MarketplaceSellerOrder> soTable = new HashMap<>();
    final Map<Long, MarketplaceOrder> orderTable = new HashMap<>();
    final List<MarketplaceReturn> returnTable = new ArrayList<>();
    final List<MarketplaceSettlementEntry> ledger = new ArrayList<>();
    final Map<Long, MarketplacePayout> payoutTable = new HashMap<>();
    final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void wire() {
        cod = new CodStandingService(entries, settings);
        svc = new MarketplaceSettlementService(lines, sellerOrders, orders, returns, entries, payouts, accounts, settings, gl,
                audit, numbers, access, txManager, cod, calendar);
        lenient().when(calendar.calendar()).thenAnswer(i -> BusinessDayCalendar.saturdaySundayWeekend(holidays));
        lenient().when(settings.codRemitDays()).thenReturn(7);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(numbers.next(anyLong(), anyString())).thenAnswer(i -> ids.incrementAndGet());
        lenient().when(settings.tPlusDays()).thenReturn(1);
        lenient().when(settings.books()).thenReturn(Optional.of(new MarketplaceSettingsService.Books(OPERATOR_ORG, ALICE)));
        lenient().when(access.userId()).thenReturn(ALICE);
        lenient().when(access.org()).thenReturn(SELLER);

        lenient().when(lines.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(lineTable.get((Long) i.getArgument(0))));
        lenient().when(lines.findDeliveredInStatus(any(), any())).thenAnswer(i -> {
            Collection<?> st = i.getArgument(0);
            return lineTable.values().stream().filter(l -> st.contains(l.getSettlementStatus())
                    && soTable.get(l.getSellerOrderId()).getDeliveredAt() != null).toList();
        });
        lenient().when(lines.findBySellerOrderIdOrderByIdAsc(anyLong())).thenAnswer(i -> lineTable.values().stream()
                .filter(l -> l.getSellerOrderId().equals(i.getArgument(0))).sorted((a, b) -> a.getId().compareTo(b.getId())).toList());
        lenient().when(lines.findBySellerOrganizationIdAndSettlementStatusAndPayoutIdIsNull(anyLong(), anyString())).thenAnswer(i -> lineTable
                .values().stream().filter(l -> l.getSellerOrganizationId().equals(i.getArgument(0))
                        && l.getSettlementStatus().equals(i.getArgument(1)) && l.getPayoutId() == null).toList());
        lenient().when(lines.findByPayoutId(anyLong())).thenAnswer(i -> lineTable.values().stream()
                .filter(l -> i.getArgument(0).equals(l.getPayoutId())).toList());
        lenient().when(lines.statement(anyLong(), any(), any(), any())).thenAnswer(i -> new PageImpl<>(lineTable
                .values().stream().filter(l -> l.getSellerOrganizationId().equals(i.getArgument(0))
                        && ((Collection<?>) i.getArgument(1)).contains(soTable.get(l.getSellerOrderId()).getAcceptanceStatus())
                        && (i.getArgument(2) == null || l.getSettlementStatus().equals(i.getArgument(2)))).toList()));
        lenient().when(sellerOrders.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(soTable.get((Long) i.getArgument(0))));
        lenient().when(orders.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(orderTable.get((Long) i.getArgument(0))));
        lenient().when(returns.findByOrderLineIdIn(any())).thenAnswer(i -> {
            Collection<?> ls = i.getArgument(0);
            return returnTable.stream().filter(r -> ls.contains(r.getOrderLineId())).toList();
        });
        lenient().when(entries.save(any())).thenAnswer(i -> {
            MarketplaceSettlementEntry e = i.getArgument(0);
            if (ledger.stream().anyMatch(x -> x.getIdempotencyKey().equals(e.getIdempotencyKey())))
                throw new org.springframework.dao.DataIntegrityViolationException("uk_mkt_entry_idem");
            e.setId(ids.incrementAndGet());
            ledger.add(e);
            return e;
        });
        lenient().when(entries.existsByIdempotencyKey(anyString())).thenAnswer(i -> ledger.stream()
                .anyMatch(e -> e.getIdempotencyKey().equals(i.getArgument(0))));
        lenient().when(entries.balance(anyLong())).thenAnswer(i -> balance((Long) i.getArgument(0)));
        lenient().when(entries.findByOrderLineIdIn(any())).thenAnswer(i -> {
            Collection<?> ls = i.getArgument(0);
            return ledger.stream().filter(e -> ls.contains(e.getOrderLineId())).toList();
        });
        lenient().when(entries.findByOrganizationIdOrderByIdDesc(anyLong(), any())).thenAnswer(i -> new PageImpl<>(ledger.stream()
                .filter(e -> e.getOrganizationId().equals(i.getArgument(0)))
                .sorted((a, b) -> b.getId().compareTo(a.getId())).toList()));                  // newest first, as the index reads
        lenient().when(entries.balances()).thenAnswer(i -> ledger.stream().map(MarketplaceSettlementEntry::getOrganizationId)
                .distinct().sorted().map(o -> new Object[] { o, balance(o) }).toList());
        lenient().when(entries.totalsOfType(anyString())).thenAnswer(i -> ledger.stream()
                .filter(e -> e.getEntryType().equals(i.getArgument(0))).map(MarketplaceSettlementEntry::getOrganizationId)
                .distinct().map(o -> new Object[] { o,
                        sumOf(o, i.getArgument(0), MarketplaceSettlementEntry::getDebitAmount),
                        sumOf(o, i.getArgument(0), MarketplaceSettlementEntry::getCreditAmount) }).toList());
        lenient().when(payouts.save(any())).thenAnswer(i -> {
            MarketplacePayout p = i.getArgument(0);
            if (p.getId() == null) p.setId(ids.incrementAndGet());
            payoutTable.put(p.getId(), p);
            return p;
        });
        lenient().when(payouts.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(payoutTable.get((Long) i.getArgument(0))));
        lenient().when(payouts.findByIdempotencyKey(anyString())).thenAnswer(i -> payoutTable.values().stream()
                .filter(p -> p.getIdempotencyKey().equals(i.getArgument(0))).findFirst());
        lenient().when(payouts.findFirstByOrganizationIdAndStatusIn(anyLong(), any())).thenAnswer(i -> payoutTable.values().stream()
                .filter(p -> p.getOrganizationId().equals(i.getArgument(0)) && ((Collection<?>) i.getArgument(1)).contains(p.getStatus()))
                .findFirst());
        lenient().when(payouts.findByOrganizationIdOrderByRequestedAtDesc(anyLong(), any())).thenAnswer(i -> new PageImpl<>(
                payoutTable.values().stream().filter(p -> p.getOrganizationId().equals(i.getArgument(0))).toList()));
        lenient().when(accounts.findByOrganizationId(anyLong())).thenReturn(Optional.empty());
    }

    BigDecimal balance(Long org) {
        return ledger.stream().filter(e -> e.getOrganizationId().equals(org))
                .map(e -> e.getCreditAmount().subtract(e.getDebitAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal sumOf(Long org, String type, java.util.function.Function<MarketplaceSettlementEntry, BigDecimal> side) {
        return ledger.stream().filter(e -> e.getOrganizationId().equals(org) && e.getEntryType().equals(type)).map(side)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal m(String v) { return new BigDecimal(v); }

    /** A delivered (or not) order: one line of {@code lineTotal}, delivery {@code fee}, 10% of items, {@code returnDays}. */
    MarketplaceOrderLine order(String mode, String lineTotal, String fee, Integer returnDays, LocalDateTime deliveredAt) {
        MarketplaceOrder o = new MarketplaceOrder();
        o.setId(ids.incrementAndGet());
        o.setOrderNo("MKT-" + o.getId());
        o.setPaymentMode(mode);
        o.setDeliveryFee(m(fee));
        orderTable.put(o.getId(), o);
        MarketplaceSellerOrder so = new MarketplaceSellerOrder();
        so.setId(ids.incrementAndGet());
        so.setMktOrderId(o.getId());
        so.setSellerOrganizationId(SELLER);
        so.setDeliveredAt(deliveredAt);
        so.setAcceptanceStatus("ACCEPTED");
        soTable.put(so.getId(), so);
        MarketplaceOrderLine l = new MarketplaceOrderLine();
        l.setId(ids.incrementAndGet());
        l.setSellerOrderId(so.getId());
        l.setSellerOrganizationId(SELLER);
        l.setProductName("Galaxy A32 128GB");
        l.setQuantity(1);
        l.setLineTotal(m(lineTotal));
        l.setReturnDays(returnDays);
        l.setCommissionBasis("ITEMS");
        l.setCommissionRate(m("0.100000"));
        l.setSettlementStatus(Settlement.NOT_ELIGIBLE.name());
        lineTable.put(l.getId(), l);
        return l;
    }

    List<MarketplaceSettlementEntry> rows(MarketplaceOrderLine l) {
        return ledger.stream().filter(e -> l.getId().equals(e.getOrderLineId())).toList();
    }

    BigDecimal row(MarketplaceOrderLine l, String type) {
        return rows(l).stream().filter(e -> e.getEntryType().equals(type))
                .map(e -> e.getCreditAmount().add(e.getDebitAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── arithmetic ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[MKT-R15.5] the worked example: 4,800 of goods + 200 delivery = 5,000; 10% of ITEMS = 480; payable 4,520; it adds up")
    void workedExample() {
        MarketplaceOrderLine l = order("CARD", "4800.00", "200.00", 7, FRIDAY);
        var f = MarketplaceSettlementService.figures(l, orderTable.values().iterator().next(), true, List.of());
        assertThat(f.breakdown().customerAmount()).isEqualByComparingTo("5000.00");
        assertThat(f.breakdown().commission()).isEqualByComparingTo("480.00");
        assertThat(f.breakdown().merchantPayable()).isEqualByComparingTo("4520.00");
        assertThat(f.breakdown().reconciles()).isTrue();
        assertThat(f.sellerCollected()).isFalse();
    }

    @Test
    @DisplayName("[MKT-R15.5] a fixed commission (the source's flat Rs 500) is the snapshot's amount, whatever the price")
    void fixedCommission() {
        MarketplaceOrderLine l = order("COD", "4800.00", "200.00", 7, FRIDAY);
        l.setCommissionBasis("FIXED");
        l.setCommissionRate(null);
        l.setCommissionFixed(m("500.00"));
        var f = MarketplaceSettlementService.figures(l, orderTable.get(soTable.get(l.getSellerOrderId()).getMktOrderId()), true, List.of());
        assertThat(f.breakdown().commission()).isEqualByComparingTo("500.00");
        assertThat(f.breakdown().merchantPayable()).isEqualByComparingTo("4500.00");
        assertThat(f.sellerCollected()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R13.2] a refunded return: commission only on what was kept; the pickup fee the customer paid stays with the seller")
    void refundedReturnReducesTheLine() {
        MarketplaceOrderLine l = order("CARD", "10000.00", "0.00", 7, FRIDAY);
        l.setQuantity(2);
        MarketplaceReturn r = new MarketplaceReturn();
        r.setOrderLineId(l.getId());
        r.setStatus(MarketplaceReturn.REFUNDED);
        r.setLineAmount(m("5000.00"));
        r.setDeduction(m("250.00"));
        r.setRefundAmount(m("4750.00"));
        var f = MarketplaceSettlementService.figures(l, orderTable.get(soTable.get(l.getSellerOrderId()).getMktOrderId()), true, List.of(r));
        assertThat(f.breakdown().customerAmount()).isEqualByComparingTo("5250.00");
        assertThat(f.breakdown().commission()).isEqualByComparingTo("500.00");
        assertThat(f.breakdown().merchantPayable()).isEqualByComparingTo("4750.00");
        assertThat(f.breakdown().reconciles()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R15.1] T+N counts BUSINESS days after the window: Friday + 0 days + T+1 = Monday; T+0 on a Saturday pays Monday")
    void eligibleOnSkipsTheWeekend() {
        BusinessDayCalendar weekends = BusinessDayCalendar.saturdaySundayWeekend(java.util.Set.of());
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 0, 1, weekends)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 1, 0, weekends).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 7, 1, weekends)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, null, 1, weekends)).as("no return policy = 7 days")
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(MarketplaceSettlementService.eligibleOn(null, 7, 1, weekends)).isNull();
    }

    @Test
    @DisplayName("[MKT-R15.1] MKT-2f: a bank holiday is skipped like a weekend: Friday + T+1 with Monday a holiday pays on Tuesday")
    void holidayIsSkipped() {
        BusinessDayCalendar mondayOff = BusinessDayCalendar.saturdaySundayWeekend(java.util.Set.of(LocalDate.of(2026, 10, 5)));
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 0, 1, mondayOff)).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 3, 0, mondayOff)).as("T+0 ending on the holiday pays the next day")
                .isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    @DisplayName("[MKT-R15.1] MKT-2f: the settlement run and the statement use the operator's holidays")
    void runUsesTheHolidays() {
        holidays.add(LocalDate.of(2026, 10, 5));                      // Monday
        MarketplaceOrderLine l = order("CARD", "4800.00", "200.00", 0, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(l.getSettlementStatus()).as("not on the holiday").isEqualTo(Settlement.PENDING_RETURN_WINDOW.name());
        assertThat(ledger).isEmpty();
        assertThat(svc.statement(null, 0, 10).getContent().get(0).eligibleOn()).isEqualTo(LocalDate.of(2026, 10, 6));
        svc.settleDue(LocalDate.of(2026, 10, 6));
        assertThat(l.getSettlementStatus()).as("the next business day").isEqualTo(Settlement.ELIGIBLE.name());
    }

    // ── the settlement run ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[MKT-R15.2] [MKT-R16.2] nothing is payable before delivery, nor inside the return window; the ledger stays empty")
    void nothingBeforeTheWindow() {
        MarketplaceOrderLine undelivered = order("COD", "5000.00", "0.00", 7, null);
        MarketplaceOrderLine inWindow = order("COD", "5000.00", "0.00", 7, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 9));
        assertThat(undelivered.getSettlementStatus()).isEqualTo(Settlement.NOT_ELIGIBLE.name());
        assertThat(inWindow.getSettlementStatus()).isEqualTo(Settlement.PENDING_RETURN_WINDOW.name());
        assertThat(ledger).isEmpty();
        verify(gl, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("[MKT-R15.5] [MKT-R22.3] a card line settles once: SALE credit, COMMISSION debit, and one journal with the cash and the commission")
    void cardLineSettlesOnce() {
        MarketplaceOrderLine l = order("CARD", "4800.00", "200.00", 0, FRIDAY);
        SettlementDTOs.RunResult r = svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(r.settled()).isEqualTo(1);
        assertThat(l.getSettlementStatus()).isEqualTo(Settlement.ELIGIBLE.name());
        assertThat(row(l, "SALE")).isEqualByComparingTo("5000.00");
        assertThat(row(l, "COMMISSION")).isEqualByComparingTo("480.00");
        assertThat(row(l, "COLLECTED_BY_SELLER")).isZero();
        assertThat(balance(SELLER)).isEqualByComparingTo("4520.00");
        ArgumentCaptor<PostingEventRequest> j = ArgumentCaptor.forClass(PostingEventRequest.class);
        verify(gl).enqueue(eq(OPERATOR_ORG), eq(ALICE), j.capture());
        assertThat(j.getValue().getEventType()).isEqualTo("MKT_SETTLEMENT");
        assertThat(j.getValue().getPaidAmount()).isEqualByComparingTo("5000.00");
        assertThat(j.getValue().getCommission()).isEqualByComparingTo("480.00");

        svc.settleDue(LocalDate.of(2026, 10, 6));
        assertThat(rows(l)).as("a second run writes nothing").hasSize(2);
        verify(gl, times(1)).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("[MKT-R15.5] (ruling R-MKT-2) cash on delivery: the rider holds the money, so the seller owes the commission (balance −480)")
    void codLineOwesTheCommission() {
        MarketplaceOrderLine l = order("COD", "4800.00", "200.00", 0, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(row(l, "COLLECTED_BY_SELLER")).isEqualByComparingTo("5000.00");
        assertThat(balance(SELLER)).isEqualByComparingTo("-480.00");
        ArgumentCaptor<PostingEventRequest> j = ArgumentCaptor.forClass(PostingEventRequest.class);
        verify(gl).enqueue(any(), any(), j.capture());
        assertThat(j.getValue().getPaidAmount()).as("no cash reached the platform").isZero();
    }

    @Test
    @DisplayName("[MKT-R15.2] [MKT-R13.2] a return in progress holds the line; once it is closed the line pays")
    void returnHoldsTheLine() {
        MarketplaceOrderLine l = order("CARD", "5000.00", "0.00", 0, FRIDAY);
        MarketplaceReturn r = new MarketplaceReturn();
        r.setOrderLineId(l.getId());
        r.setStatus(MarketplaceReturn.APPROVED);
        returnTable.add(r);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(l.getSettlementStatus()).isEqualTo(Settlement.ON_HOLD.name());
        assertThat(ledger).isEmpty();
        r.setStatus(MarketplaceReturn.REJECTED);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(l.getSettlementStatus()).isEqualTo(Settlement.ELIGIBLE.name());
    }

    @Test
    @DisplayName("[MKT-R15.3] no books chosen yet: a due line waits rather than settling into nobody's ledger")
    void noBooksNoSettlement() {
        when(settings.books()).thenReturn(Optional.empty());
        MarketplaceOrderLine l = order("CARD", "5000.00", "0.00", 0, FRIDAY);
        SettlementDTOs.RunResult r = svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(r.waitingForBooks()).isEqualTo(1);
        assertThat(l.getSettlementStatus()).isEqualTo(Settlement.PENDING_RETURN_WINDOW.name());
        assertThat(ledger).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R16.1] [MKT-R15.5] the statement adds up on every line, before and after settlement")
    void statementAddsUp() {
        order("CARD", "4800.00", "200.00", 0, FRIDAY);
        order("COD", "1000.00", "0.00", 7, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        var page = svc.statement(null, 0, 20);
        assertThat(page.getContent()).hasSize(2);
        for (SettlementDTOs.StatementLine s : page.getContent()) {
            BigDecimal parts = s.payable().add(s.commission()).add(s.delivery()).add(s.fees()).add(s.tax()).add(s.reserve())
                    .add(s.adjustment());
            assertThat(parts).isEqualByComparingTo(s.customerAmount());
            assertThat(s.eligibleOn()).isAfterOrEqualTo(s.deliveredAt().toLocalDate());
        }
    }

    // ── payouts ────────────────────────────────────────────────────────────────────────────────────────────

    MarketplacePayout settledCardBalance() {
        order("CARD", "4800.00", "200.00", 0, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        return null;
    }

    @Test
    @DisplayName("[MKT-R16.3] a payout with no positive balance is refused, in a sentence")
    void nothingToPay() {
        assertThatThrownBy(() -> svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k1")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("nothing to pay");
    }

    @Test
    @DisplayName("[MKT-R16.3] [MKT-R22.3] the same key is the same payout; one in flight per seller; the requester cannot approve it")
    void payoutIdempotentAndFourEyes() {
        settledCardBalance();
        SettlementDTOs.PayoutView a = svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k1"));
        SettlementDTOs.PayoutView b = svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k1"));
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(a.requestedAmount()).isEqualByComparingTo("4520.00");
        assertThatThrownBy(() -> svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k2")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("still requested");
        assertThatThrownBy(() -> svc.approvePayout(a.id())).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Another person must approve");

        when(access.userId()).thenReturn(BOB);
        assertThat(svc.approvePayout(a.id()).status()).isEqualTo(MarketplacePayout.APPROVED);
        assertThat(lineTable.values()).allMatch(l -> l.getSettlementStatus().equals(Settlement.APPROVED.name()));
    }

    @Test
    @DisplayName("[MKT-R16.3] [MKT-R16.2] marking paid needs the bank reference, writes ONE payout row and ONE journal, and settles the lines")
    void markPaidOnce() {
        settledCardBalance();
        SettlementDTOs.PayoutView po = svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k1"));
        assertThatThrownBy(() -> svc.markPaid(new SettlementDTOs.PayoutDecision(po.id(), "TRX-1")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("must be approved");
        when(access.userId()).thenReturn(BOB);
        svc.approvePayout(po.id());
        assertThatThrownBy(() -> svc.markPaid(new SettlementDTOs.PayoutDecision(po.id(), " ")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("bank's reference");
        SettlementDTOs.PayoutView paid = svc.markPaid(new SettlementDTOs.PayoutDecision(po.id(), "TRX-123"));
        svc.markPaid(new SettlementDTOs.PayoutDecision(po.id(), "TRX-123"));
        assertThat(paid.status()).isEqualTo(MarketplacePayout.PAID);
        assertThat(paid.bankReference()).isEqualTo("TRX-123");
        assertThat(ledger.stream().filter(e -> e.getEntryType().equals("PAYOUT"))).hasSize(1);
        assertThat(balance(SELLER)).isZero();
        assertThat(lineTable.values()).allMatch(l -> l.getSettlementStatus().equals(Settlement.PAID.name()));
        ArgumentCaptor<PostingEventRequest> j = ArgumentCaptor.forClass(PostingEventRequest.class);
        verify(gl, times(2)).enqueue(any(), any(), j.capture());
        assertThat(j.getAllValues().get(1).getEventType()).isEqualTo("MKT_PAYOUT");
        assertThat(j.getAllValues().get(1).getGrandTotal()).isEqualByComparingTo("4520.00");
    }

    @Test
    @DisplayName("[MKT-R16.3] a correction after approval that shrinks the balance stops the payout: never pay more than is owed")
    void markPaidRefusedWhenBalanceShrank() {
        settledCardBalance();
        SettlementDTOs.PayoutView po = svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k1"));
        when(access.userId()).thenReturn(BOB);
        svc.approvePayout(po.id());
        svc.adjust(new SettlementDTOs.AdjustmentRequest(SELLER, m("-100.00"), "damaged on arrival", "a1"));
        assertThatThrownBy(() -> svc.markPaid(new SettlementDTOs.PayoutDecision(po.id(), "TRX-9")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("less than this payout");
    }

    // ── corrections ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[MKT-R15.6] a correction is a NEW row, recorded once per key; the original rows are untouched")
    void correctionIsANewRow() {
        settledCardBalance();
        List<MarketplaceSettlementEntry> before = List.copyOf(ledger);
        svc.adjust(new SettlementDTOs.AdjustmentRequest(SELLER, m("-100.00"), "walk correction", "a1"));
        svc.adjust(new SettlementDTOs.AdjustmentRequest(SELLER, m("-100.00"), "walk correction", "a1"));
        assertThat(ledger).hasSize(before.size() + 1);
        MarketplaceSettlementEntry adj = ledger.get(ledger.size() - 1);
        assertThat(adj.getEntryType()).isEqualTo("ADJUSTMENT");
        assertThat(adj.getDebitAmount()).isEqualByComparingTo("100.00");
        assertThat(balance(SELLER)).isEqualByComparingTo("4420.00");
        assertThatThrownBy(() -> svc.adjust(new SettlementDTOs.AdjustmentRequest(SELLER, BigDecimal.ZERO, "x y z", "a2")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> svc.adjust(new SettlementDTOs.AdjustmentRequest(SELLER, m("5"), "", "a3")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Say why");
    }

    @Test
    @DisplayName("[MKT-R15.6] [MKT-R22.3] the ledger repository has no update or delete, and every column is insert-only")
    void ledgerHasNoEditPath() throws Exception {
        for (Method method : MarketplaceSettlementEntryRepository.class.getMethods()) {
            assertThat(method.getName()).doesNotStartWith("delete").doesNotStartWith("update");
        }
        for (var f : MarketplaceSettlementEntry.class.getDeclaredFields()) {
            jakarta.persistence.Column c = f.getAnnotation(jakarta.persistence.Column.class);
            if (c != null) assertThat(c.updatable()).as(f.getName()).isFalse();
        }
    }

    @Test
    @DisplayName("operator-only: a tenant asking for payouts or corrections gets the same 403")
    void operatorOnly() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> svc.requestPayout(new SettlementDTOs.PayoutRequest(SELLER, "k"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.adjust(new SettlementDTOs.AdjustmentRequest(SELLER, m("1"), "abc", "k"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.accounts()).isInstanceOf(AccessDeniedException.class);
    }

    // ── MKT-2d: cash orders, what a seller owes, and its payment ─────────────────────────────────────────────

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    /** A cash order settled {@code daysAgo} days before TODAY: the seller owes its 480 commission from that day. */
    MarketplaceOrderLine owing(int daysAgo) {
        int before = ledger.size();
        MarketplaceOrderLine l = order("COD", "4800.00", "200.00", 0, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        ledger.subList(before, ledger.size()).forEach(e -> e.setEffectiveAt(TODAY.minusDays(daysAgo).atTime(10, 0)));
        return l;
    }

    SettlementDTOs.AccountView pay(String amount, String ref, String note, String key) {
        return svc.recordRemittance(new SettlementDTOs.RemittanceRequest(SELLER, m(amount), ref, note, key));
    }

    /** The REMITTANCE rows the service just wrote are dated {@code daysAgo} before TODAY. */
    void dateLatest(int daysAgo) {
        ledger.stream().filter(e -> "REMITTANCE".equals(e.getEntryType()) && e.getEffectiveAt().toLocalDate().isAfter(TODAY))
                .forEach(e -> e.setEffectiveAt(TODAY.minusDays(daysAgo).atTime(12, 0)));
    }

    @Test
    @DisplayName("[MKT-R20.3] cash orders: owed = the negative balance, since the day it went negative; pay-by = + 7 days; overdue after it")
    void owedSinceAndOverdue() {
        owing(10);
        SettlementDTOs.CodStanding s = cod.standing(SELLER, balance(SELLER), TODAY);
        assertThat(s.owed()).isEqualByComparingTo("480.00");
        assertThat(s.owedSince()).isEqualTo(TODAY.minusDays(10));
        assertThat(s.payBy()).isEqualTo(TODAY.minusDays(3));
        assertThat(s.overdue()).isTrue();
        assertThat(s.codStopped()).as("the stop switch is off by default").isFalse();
        assertThat(cod.standing(SELLER, balance(SELLER), TODAY.minusDays(3)).overdue()).as("the pay-by day itself is in time").isFalse();
        org.mockito.Mockito.when(settings.codStopWhenOverdue()).thenReturn(true);
        assertThat(cod.standing(SELLER, balance(SELLER), TODAY).codStopped()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R20.3] a newer cash order adds to the debt but does not make it newer; a seller owed money owes nothing")
    void newerOrderKeepsTheDate() {
        owing(10);
        owing(2);
        SettlementDTOs.CodStanding s = cod.standing(SELLER, balance(SELLER), TODAY);
        assertThat(s.owed()).isEqualByComparingTo("960.00");
        assertThat(s.owedSince()).isEqualTo(TODAY.minusDays(10));
        order("CARD", "9600.00", "0.00", 0, FRIDAY);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        assertThat(balance(SELLER)).isPositive();
        SettlementDTOs.CodStanding none = cod.standing(SELLER, balance(SELLER), TODAY);
        assertThat(none.owed()).isZero();
        assertThat(none.owedSince()).isNull();
        assertThat(none.overdue()).isFalse();
    }

    @Test
    @DisplayName("[MKT-R20.3] [MKT-R15.6] the seller pays: a REMITTANCE credit, one journal (Dr bank, Cr 2400), audited; the same key once")
    void remittanceRecordedOnce() {
        owing(10);
        SettlementDTOs.AccountView v = pay("480.00", "HBL-123", null, "r1");
        assertThat(v.balance()).isZero();
        assertThat(v.cod().owed()).isZero();
        assertThat(ledger.stream().filter(e -> "REMITTANCE".equals(e.getEntryType())).toList()).singleElement().satisfies(e -> {
            assertThat(e.getCreditAmount()).isEqualByComparingTo("480.00");
            assertThat(e.getDebitAmount()).isZero();
            assertThat(e.getRef()).startsWith("RM-");
            assertThat(e.getMemo()).contains("HBL-123");
            assertThat(e.getOrderLineId()).isNull();
        });
        ArgumentCaptor<PostingEventRequest> j = ArgumentCaptor.forClass(PostingEventRequest.class);
        verify(gl, times(2)).enqueue(any(), any(), j.capture());
        PostingEventRequest rem = j.getAllValues().get(1);
        assertThat(rem.getEventType()).isEqualTo("MKT_REMITTANCE");
        assertThat(rem.getGrandTotal()).isEqualByComparingTo("480.00");
        verify(audit).event(eq("MKT_REMITTANCE_RECORDED"), any(), any(), eq(SELLER), any(), any(), any(), any(), any());
        pay("480.00", "HBL-123", null, "r1");                               // a double click
        assertThat(ledger.stream().filter(e -> "REMITTANCE".equals(e.getEntryType())).count()).isEqualTo(1);
        verify(gl, times(2)).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("[MKT-R20.3] a part payment says why, and keeps the debt's date: smaller, not newer")
    void partPayment() {
        owing(10);
        assertThatThrownBy(() -> pay("200.00", "HBL-1", "", "p0")).isInstanceOf(ValidationException.class)
                .hasMessage("The seller owes Rs 480.00 and paid Rs 200.00. Say why it paid less: the note is shown on the seller's statement.");
        pay("200.00", "HBL-1", "Rider still holds two orders' cash", "p1");
        dateLatest(1);
        SettlementDTOs.CodStanding s = cod.standing(SELLER, balance(SELLER), TODAY);
        assertThat(s.owed()).isEqualByComparingTo("280.00");
        assertThat(s.owedSince()).isEqualTo(TODAY.minusDays(10));
        assertThat(s.overdue()).isTrue();
    }

    @Test
    @DisplayName("[MKT-R20.3] once squared, a new debt starts its own clock")
    void newDebtNewClock() {
        owing(20);
        pay("480.00", "HBL-1", null, "q1");
        dateLatest(15);
        owing(2);
        SettlementDTOs.CodStanding s = cod.standing(SELLER, balance(SELLER), TODAY);
        assertThat(s.owed()).isEqualByComparingTo("480.00");
        assertThat(s.owedSince()).isEqualTo(TODAY.minusDays(2));
        assertThat(s.overdue()).isFalse();
    }

    @Test
    @DisplayName("[MKT-R20.3] a payment is refused, in a sentence: more than owed, nothing owed, no amount, no reference")
    void remittanceRefusals() {
        assertThatThrownBy(() -> pay("10.00", "HBL-1", null, "n0")).isInstanceOf(ValidationException.class)
                .hasMessage("This seller owes nothing for cash orders.");
        owing(3);
        assertThatThrownBy(() -> pay("480.01", "HBL-1", null, "n1")).isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("The seller owes Rs 480.00. Enter at most that");
        assertThatThrownBy(() -> pay("0", "HBL-1", null, "n2")).isInstanceOf(ValidationException.class)
                .hasMessage("Enter the amount the seller paid.");
        assertThatThrownBy(() -> pay("480.00", " ", null, "n3")).isInstanceOf(ValidationException.class)
                .hasMessage("Enter the bank's reference, or the receipt number for cash.");
        assertThat(ledger.stream().filter(e -> "REMITTANCE".equals(e.getEntryType()))).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R20.3] the operator's list: cash collected, paid, owed and since when; overdue first; a card-only seller is not on it")
    void codReconciliationRows() {
        owing(10);
        pay("100.00", "HBL-1", "part", "c1");
        dateLatest(5);
        MarketplaceOrderLine card = order("CARD", "1000.00", "0.00", 0, FRIDAY);
        card.setSellerOrganizationId(8L);
        svc.settleDue(LocalDate.of(2026, 10, 5));
        List<SettlementDTOs.CodRow> rows = svc.codReconciliation();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.organizationId()).isEqualTo(SELLER);
            assertThat(r.cashCollected()).isEqualByComparingTo("5000.00");
            assertThat(r.remitted()).isEqualByComparingTo("100.00");
            assertThat(r.balance()).isEqualByComparingTo("-380.00");
            assertThat(r.standing().owed()).isEqualByComparingTo("380.00");
        });
    }

    @Test
    @DisplayName("[MKT-R20.3] owed-since across pages: one line's rows are one step even when a page splits them")
    void walkAcrossPages() {
        // oldest → newest: −50 (day 30); line 9 settles on day 20: SALE +1000, COMMISSION −100, COLLECTED −1000 (→ −150);
        // −300 (day 4) → −450. Read newest first, two rows a page, the page boundary inside line 9.
        MarketplaceSettlementEntry older = entry(null, "50.00", "0.00", 30);
        MarketplaceSettlementEntry sale = entry(9L, "0.00", "1000.00", 20);
        MarketplaceSettlementEntry fee = entry(9L, "100.00", "0.00", 20);
        MarketplaceSettlementEntry cash = entry(9L, "1000.00", "0.00", 20);
        MarketplaceSettlementEntry debt = entry(null, "300.00", "0.00", 4);
        CodStandingService.Walk w = CodStandingService.walk(new CodStandingService.Walk(null, m("-450.00"), false, null),
                List.of(debt, cash));
        assertThat(w.done()).isFalse();
        w = CodStandingService.walk(w, List.of(fee, sale));
        assertThat(w.done()).as("inside line 9 the balance is briefly +950, but the line is one step").isFalse();
        assertThat(w.balanceBefore()).isEqualByComparingTo("-50.00");
        w = CodStandingService.walk(w, List.of(older));
        assertThat(w.since()).as("never back to zero since day 30").isEqualTo(TODAY.minusDays(30));
        // the balance before the very first row is zero: the next page (none) would stop there
        assertThat(w.balanceBefore()).isZero();
    }

    MarketplaceSettlementEntry entry(Long line, String debit, String credit, int daysAgo) {
        MarketplaceSettlementEntry e = new MarketplaceSettlementEntry();
        e.setOrderLineId(line);
        e.setDebitAmount(m(debit));
        e.setCreditAmount(m(credit));
        e.setEffectiveAt(TODAY.minusDays(daysAgo).atStartOfDay());
        return e;
    }

    @Test
    @DisplayName("operator-only: a tenant recording a payment or reading what sellers owe gets the same 403")
    void codOperatorOnly() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> pay("1.00", "x", null, "k")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.codReconciliation()).isInstanceOf(AccessDeniedException.class);
    }
}
