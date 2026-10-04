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
    MarketplaceSettlementService svc;

    final Map<Long, MarketplaceOrderLine> lineTable = new HashMap<>();
    final Map<Long, MarketplaceSellerOrder> soTable = new HashMap<>();
    final Map<Long, MarketplaceOrder> orderTable = new HashMap<>();
    final List<MarketplaceReturn> returnTable = new ArrayList<>();
    final List<MarketplaceSettlementEntry> ledger = new ArrayList<>();
    final Map<Long, MarketplacePayout> payoutTable = new HashMap<>();
    final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void wire() {
        svc = new MarketplaceSettlementService(lines, sellerOrders, orders, returns, entries, payouts, accounts, settings, gl,
                audit, numbers, access, txManager);
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
                .filter(e -> e.getOrganizationId().equals(i.getArgument(0))).toList()));
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
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 0, 1)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 1, 0).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, 7, 1)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(MarketplaceSettlementService.eligibleOn(FRIDAY, null, 1)).as("no return policy = 7 days")
                .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(MarketplaceSettlementService.eligibleOn(null, 7, 1)).isNull();
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
}
