package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.SupportDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceCustomer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceOrderLine;
import com.myplus.marketplace.multiseller.entity.MarketplaceReturn;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerOrder;
import com.myplus.marketplace.multiseller.entity.MarketplaceSupportCase;
import com.myplus.marketplace.multiseller.entity.MarketplaceSupportMessage;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderLineRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceReturnRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerOrderRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSupportCaseRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSupportMessageRepository;
import com.myplus.marketplace.service.OrderService;

/**
 * MKT-1f — support cases and returns through the REAL service, the tables in memory, the edges (payments, the
 * seller's books, settings, audit) mocked. Each test builds its own delivered order.
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceSupportServiceTest {

    static final long SELLER = 7L, OTHER_SELLER = 8L, STORE_ORDER = 900L, SOURCE_PRODUCT = 555L;

    @Mock MarketplaceSupportCaseRepository cases;
    @Mock MarketplaceSupportMessageRepository messages;
    @Mock MarketplaceReturnRepository returns;
    @Mock MarketplaceOrderRepository orders;
    @Mock MarketplaceSellerOrderRepository sellerOrders;
    @Mock MarketplaceOrderLineRepository lines;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplacePaymentService payments;
    @Mock MarketplaceAuditService audit;
    @Mock OrderService storeOrders;
    @Mock DocumentNumberService numbers;
    @Mock SellerAccess access;
    @Mock PlatformTransactionManager txManager;
    MarketplaceSupportService svc;

    final Map<Long, MarketplaceSupportCase> caseTable = new HashMap<>();
    final List<MarketplaceSupportMessage> msgTable = new ArrayList<>();
    final Map<Long, MarketplaceReturn> returnTable = new HashMap<>();
    final Map<Long, MarketplaceOrder> orderTable = new HashMap<>();
    final Map<Long, MarketplaceSellerOrder> soTable = new HashMap<>();
    final Map<Long, MarketplaceOrderLine> lineTable = new HashMap<>();
    final AtomicLong ids = new AtomicLong(100);
    MarketplaceCustomer ali, sara;

    @BeforeEach
    void wire() {
        svc = new MarketplaceSupportService(cases, messages, returns, orders, sellerOrders, lines, accounts, settings, payments,
                audit, storeOrders, numbers, access, txManager);
        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(numbers.next(anyLong(), anyString())).thenAnswer(i -> ids.incrementAndGet());
        lenient().when(settings.changeOfMindFee()).thenReturn(new BigDecimal("250.00"));
        lenient().when(access.org()).thenReturn(SELLER);
        lenient().when(access.userId()).thenReturn(70L);
        MarketplaceSellerAccount acct = new MarketplaceSellerAccount();
        acct.setDisplayName("Shahzad Mobile Shop");
        lenient().when(accounts.findByOrganizationId(SELLER)).thenReturn(Optional.of(acct));

        lenient().when(cases.save(any())).thenAnswer(i -> put(caseTable, i.getArgument(0)));
        lenient().when(cases.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(caseTable.get((Long) i.getArgument(0))));
        lenient().when(cases.findByCaseNo(anyString())).thenAnswer(i -> caseTable.values().stream()
                .filter(c -> c.getCaseNo().equals(i.getArgument(0))).findFirst());
        lenient().when(cases.findFirstByMktOrderIdAndStatusNotOrderByIdDesc(anyLong(), anyString())).thenAnswer(i -> caseTable.values()
                .stream().filter(c -> c.getMktOrderId().equals(i.getArgument(0)) && !c.getStatus().equals(i.getArgument(1))).findFirst());
        lenient().when(cases.findByStatusInOrderByUrgentDescCreatedAtAsc(any(), any())).thenAnswer(i -> {
            Collection<String> st = i.getArgument(0);
            return new PageImpl<>(caseTable.values().stream().filter(c -> st.contains(c.getStatus()))
                    .sorted((a, b) -> a.isUrgent() == b.isUrgent() ? a.getId().compareTo(b.getId()) : a.isUrgent() ? -1 : 1).toList());
        });
        lenient().when(cases.findTop100BySellerOrgIdAndStatusOrderByCreatedAtAsc(anyLong(), anyString())).thenAnswer(i -> caseTable.values()
                .stream().filter(c -> c.getSellerOrgId().equals(i.getArgument(0)) && c.getStatus().equals(i.getArgument(1))).toList());
        lenient().when(messages.save(any())).thenAnswer(i -> { MarketplaceSupportMessage m = i.getArgument(0); m.setId(ids.incrementAndGet()); msgTable.add(m); return m; });
        lenient().when(messages.findByCaseIdOrderByIdAsc(anyLong())).thenAnswer(i -> msgTable.stream()
                .filter(m -> m.getCaseId().equals(i.getArgument(0))).toList());
        lenient().when(messages.findByCaseIdAndVisibleToCustomerTrueOrderByIdAsc(anyLong())).thenAnswer(i -> msgTable.stream()
                .filter(m -> m.getCaseId().equals(i.getArgument(0)) && m.isVisibleToCustomer()).toList());
        lenient().when(returns.save(any())).thenAnswer(i -> put(returnTable, i.getArgument(0)));
        lenient().when(returns.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(returnTable.get((Long) i.getArgument(0))));
        lenient().when(returns.findByReturnNo(anyString())).thenAnswer(i -> returnTable.values().stream()
                .filter(r -> r.getReturnNo().equals(i.getArgument(0))).findFirst());
        lenient().when(returns.findByCaseIdOrderByIdAsc(anyLong())).thenAnswer(i -> returnTable.values().stream()
                .filter(r -> r.getCaseId().equals(i.getArgument(0))).toList());
        lenient().when(returns.existsByOrderLineIdAndStatusIn(anyLong(), any())).thenAnswer(i -> returnTable.values().stream()
                .anyMatch(r -> r.getOrderLineId().equals(i.getArgument(0)) && ((Collection<?>) i.getArgument(1)).contains(r.getStatus())));
        lenient().when(returns.findTop100BySellerOrgIdAndStatusOrderByCreatedAtAsc(anyLong(), anyString())).thenAnswer(i -> returnTable
                .values().stream().filter(r -> r.getSellerOrgId().equals(i.getArgument(0)) && r.getStatus().equals(i.getArgument(1))).toList());
        lenient().when(orders.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(orderTable.get((Long) i.getArgument(0))));
        lenient().when(orders.findByOrderNo(anyString())).thenAnswer(i -> orderTable.values().stream()
                .filter(o -> o.getOrderNo().equals(i.getArgument(0))).findFirst());
        lenient().when(sellerOrders.findByMktOrderId(anyLong())).thenAnswer(i -> soTable.values().stream()
                .filter(s -> s.getMktOrderId().equals(i.getArgument(0))).toList());
        lenient().when(lines.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(lineTable.get((Long) i.getArgument(0))));
        lenient().when(storeOrders.marketplaceReturn(anyLong(), anyLong(), anyLong(), anyInt(), anyBoolean(), anyString()))
                .thenReturn("CRN-000001");

        ali = customer(1L);
        sara = customer(2L);
    }

    // ── fixtures ──

    record Placed(MarketplaceOrder order, MarketplaceSellerOrder so, MarketplaceOrderLine line) { }

    /** A delivered order for {@code c}, one line of {@code qty} at 52,000, the line's return days {@code days}. */
    Placed delivered(MarketplaceCustomer c, String paymentMode, int qty, Integer days, LocalDateTime deliveredAt) {
        MarketplaceOrder o = new MarketplaceOrder();
        o.setId(ids.incrementAndGet());
        o.setOrderNo("MKT-" + o.getId());
        o.setCustomerId(c.getId());
        o.setStatus("CONFIRMED");
        o.setPaymentMode(paymentMode);
        o.setCustomerName("Ali Raza");
        o.setCustomerPhone("03001112223");
        orderTable.put(o.getId(), o);
        MarketplaceSellerOrder so = new MarketplaceSellerOrder();
        so.setId(ids.incrementAndGet());
        so.setMktOrderId(o.getId());
        so.setSellerOrganizationId(SELLER);
        so.setAcceptanceStatus("ACCEPTED");
        so.setStoreOrderId(STORE_ORDER);
        so.setDeliveredAt(deliveredAt);
        soTable.put(so.getId(), so);
        MarketplaceOrderLine l = new MarketplaceOrderLine();
        l.setId(ids.incrementAndGet());
        l.setSellerOrderId(so.getId());
        l.setSourceProductId(SOURCE_PRODUCT);
        l.setProductName("Samsung Galaxy A32");
        l.setQuantity(qty);
        l.setUnitPrice(new BigDecimal("52000.00"));
        l.setSellerOrganizationId(SELLER);
        l.setStockOwnerOrganizationId(SELLER);
        l.setCustodianOrganizationId(SELLER);
        l.setFulfillerOrganizationId(SELLER);
        l.setReturnDays(days);
        lineTable.put(l.getId(), l);
        return new Placed(o, so, l);
    }

    Placed delivered(MarketplaceCustomer c, String mode) {
        return delivered(c, mode, 1, 7, LocalDateTime.now().minusDays(1));
    }

    SupportDTOs.CaseView returnOf(Placed p, String reason) {
        return svc.open(ali, p.order().getOrderNo(), new SupportDTOs.OpenCaseRequest("RETURN", "see photos", p.line().getId(), 1, reason));
    }

    // ── R8.2 ──

    @Test
    @DisplayName("[MKT-R8.2] one case per order; the customer never sees an internal note; the seller's answer reaches them as MaxTheService")
    void oneCaseNoInternalNotes() {
        Placed p = delivered(ali, "COD");
        SupportDTOs.CaseView first = svc.open(ali, p.order().getOrderNo(), new SupportDTOs.OpenCaseRequest("ORDER_PROBLEM", "Box was open", null, null, null));
        SupportDTOs.CaseView again = svc.open(ali, p.order().getOrderNo(), new SupportDTOs.OpenCaseRequest("ORDER_PROBLEM", "again", null, null, null));
        assertThat(again.caseNo()).isEqualTo(first.caseNo());
        svc.reply(new SupportDTOs.ReplyRequest(first.caseNo(), "seller has a history of this", true));
        svc.task(new SupportDTOs.TaskRequest(first.caseNo(), "Check the box"));
        svc.taskReply(new SupportDTOs.TaskReplyRequest(first.caseNo(), "Charger sent today"));
        SupportDTOs.CaseView mine = svc.myCase(ali, first.caseNo());
        assertThat(mine.messages()).extracting(SupportDTOs.MessageView::body)
                .contains("Charger sent today").noneMatch(b -> b.contains("history") || b.contains("Check the box"));
        assertThat(mine.messages()).filteredOn(m -> m.body().equals("Charger sent today"))
                .extracting(SupportDTOs.MessageView::from).containsOnly(MarketplaceSupportService.SUPPORT);
        assertThat(svc.operatorView(first.caseNo()).messages()).anySatisfy(m -> assertThat(m.internal()).isTrue());
    }

    @Test
    @DisplayName("[MKT-R22.1] another customer can neither open a case on my order nor read my case; another seller cannot answer it")
    void isolation() {
        Placed p = delivered(ali, "COD");
        SupportDTOs.CaseView c = svc.open(ali, p.order().getOrderNo(), new SupportDTOs.OpenCaseRequest("OTHER", "question", null, null, null));
        assertThatThrownBy(() -> svc.open(sara, p.order().getOrderNo(), new SupportDTOs.OpenCaseRequest("OTHER", "x", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("No such order.");
        assertThatThrownBy(() -> svc.myCase(sara, c.caseNo())).hasMessage("No such case.");
        svc.task(new SupportDTOs.TaskRequest(c.caseNo(), "call them"));
        when(access.org()).thenReturn(OTHER_SELLER);
        assertThat(svc.tasks()).isEmpty();
        assertThatThrownBy(() -> svc.taskReply(new SupportDTOs.TaskReplyRequest(c.caseNo(), "not mine"))).hasMessage("No such case.");
        when(access.org()).thenReturn(SELLER);
        assertThat(svc.tasks()).extracting(SupportDTOs.SellerTask::caseNo).containsExactly(c.caseNo());   // positive control
    }

    @Test
    @DisplayName("[MKT-R22.1] the operator's actions are refused to anyone but the operator")
    void operatorOnly() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> svc.queue(null, 0, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> svc.decide(new SupportDTOs.DecisionRequest("RT-1", "APPROVED", null))).isInstanceOf(AccessDeniedException.class);
    }

    // ── R13.1 / R13.3 ──

    @Test
    @DisplayName("[MKT-R13.1] the bearer follows the cause and is resolved from the line's snapshot when the return is opened")
    void bearerFromSnapshot() {
        Placed p = delivered(ali, "CARD");
        p.line().setFulfillerOrganizationId(SELLER);
        p.line().setStockOwnerOrganizationId(42L);                                // a different owner on the snapshot
        SupportDTOs.ReturnView wrong = returnOf(p, "WRONG_PRODUCT").returns().get(0);
        assertThat(wrong.bearerRole()).isEqualTo("FULFILLER");
        assertThat(returnTable.values()).singleElement().satisfies(r -> assertThat(r.getBearerOrgId()).isEqualTo(SELLER));
        Placed q = delivered(ali, "CARD");
        q.line().setStockOwnerOrganizationId(42L);
        returnOf(q, "DEFECTIVE");
        assertThat(returnTable.values()).filteredOn(r -> r.getReason().equals("DEFECTIVE")).singleElement()
                .satisfies(r -> assertThat(r.getBearerOrgId()).isEqualTo(42L));
        q.line().setStockOwnerOrganizationId(SELLER);                              // a later change never moves an old return
        assertThat(returnTable.values()).filteredOn(r -> r.getReason().equals("DEFECTIVE")).singleElement()
                .satisfies(r -> assertThat(r.getBearerOrgId()).isEqualTo(42L));
    }

    @Test
    @DisplayName("[MKT-R13.1] change of mind: the customer bears the pickup fee, deducted from the refund")
    void changeOfMindFee() {
        Placed p = delivered(ali, "CARD");
        SupportDTOs.ReturnView r = returnOf(p, "CHANGE_OF_MIND").returns().get(0);
        assertThat(r.bearerRole()).isEqualTo("CUSTOMER");
        assertThat(r.deduction()).isEqualByComparingTo("250");
        assertThat(r.refundAmount()).isEqualByComparingTo("51750");
    }

    @Test
    @DisplayName("[MKT-R13.3] the return window is the line's snapshot: change of mind is refused past it, a fault is still taken")
    void windowFromSnapshot() {
        Placed none = delivered(ali, "COD", 1, 0, LocalDateTime.now().minusHours(1));
        assertThatThrownBy(() -> returnOf(none, "CHANGE_OF_MIND")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("The return period for this item ended on");
        assertThat(returnOf(none, "NOT_AS_DESCRIBED").returns()).hasSize(1);
        Placed late = delivered(ali, "COD", 1, 7, LocalDateTime.now().minusDays(8));
        assertThatThrownBy(() -> returnOf(late, "CHANGE_OF_MIND")).hasMessageContaining("ended on");
        Placed inside = delivered(ali, "COD", 1, 7, LocalDateTime.now().minusDays(6));
        assertThat(returnOf(inside, "CHANGE_OF_MIND").returns()).hasSize(1);       // positive control
    }

    @Test
    @DisplayName("[MKT-R13.2] nothing is returnable before delivery, and one line has one return in progress")
    void deliveredAndOnce() {
        Placed undelivered = delivered(ali, "COD", 1, 7, null);
        assertThatThrownBy(() -> returnOf(undelivered, "DEFECTIVE")).hasMessageContaining("once it has been delivered");
        Placed p = delivered(ali, "COD");
        returnOf(p, "DEFECTIVE");
        assertThatThrownBy(() -> returnOf(p, "DEFECTIVE")).hasMessageContaining("already has a return in progress");
    }

    @Test
    @DisplayName("[MKT-R13.4] expired or unsafe goods make the case urgent: first in the operator's queue")
    void urgentFirst() {
        Placed a = delivered(ali, "COD");
        svc.open(ali, a.order().getOrderNo(), new SupportDTOs.OpenCaseRequest("OTHER", "question", null, null, null));
        Placed b = delivered(ali, "COD");
        String urgent = returnOf(b, "EXPIRED_OR_UNSAFE").caseNo();
        assertThat(svc.queue(null, 0, 10).getContent().get(0).caseNo()).isEqualTo(urgent);
    }

    // ── R13.2: money and books ──

    @Test
    @DisplayName("[MKT-R13.2] a card return: credit note once, refunded once, however often 'Item received' is pressed")
    void cardRefundOnce() {
        Placed p = delivered(ali, "CARD");
        String no = returnOf(p, "WRONG_PRODUCT").returns().get(0).returnNo();
        svc.decide(new SupportDTOs.DecisionRequest(no, "APPROVED", null));
        when(payments.refundReturn(eq(p.order().getId()), anyLong(), any(), anyString())).thenReturn(true);
        assertThat(svc.received(new SupportDTOs.ReceivedRequest(no, "RESTOCK", null)).status()).isEqualTo("REFUNDED");
        assertThat(svc.received(new SupportDTOs.ReceivedRequest(no, "RESTOCK", null)).status()).isEqualTo("REFUNDED");
        verify(storeOrders, times(1)).marketplaceReturn(eq(STORE_ORDER), eq(SELLER), eq(SOURCE_PRODUCT), eq(1), eq(false), anyString());
        verify(payments, times(1)).refundReturn(eq(p.order().getId()), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("[MKT-R13.2] a card refund whose answer is lost leaves the return RECEIVED; a retry never raises a second credit note")
    void lostRefundNoSecondCreditNote() {
        Placed p = delivered(ali, "CARD");
        String no = returnOf(p, "DEFECTIVE").returns().get(0).returnNo();
        svc.decide(new SupportDTOs.DecisionRequest(no, "APPROVED", null));
        when(payments.refundReturn(anyLong(), anyLong(), any(), anyString())).thenReturn(false, true);
        assertThat(svc.received(new SupportDTOs.ReceivedRequest(no, "QUARANTINE", null)).status()).isEqualTo("RECEIVED");
        assertThat(svc.received(new SupportDTOs.ReceivedRequest(no, "QUARANTINE", null)).status()).isEqualTo("REFUNDED");
        verify(storeOrders, times(1)).marketplaceReturn(anyLong(), anyLong(), anyLong(), anyInt(), eq(true), anyString());
    }

    @Test
    @DisplayName("[MKT-R13.2] cash on delivery: refused until the rider has handed the cash back; never a card refund (R-MKT-12)")
    void codCashAtPickup() {
        Placed p = delivered(ali, "COD");
        String no = returnOf(p, "DEFECTIVE").returns().get(0).returnNo();
        svc.decide(new SupportDTOs.DecisionRequest(no, "APPROVED", null));
        assertThatThrownBy(() -> svc.received(new SupportDTOs.ReceivedRequest(no, "QUARANTINE", false)))
                .hasMessageContaining("hand Rs 52,000 back to the customer at pickup");
        verify(storeOrders, never()).marketplaceReturn(anyLong(), anyLong(), anyLong(), anyInt(), anyBoolean(), anyString());
        SupportDTOs.ReturnView v = svc.received(new SupportDTOs.ReceivedRequest(no, "QUARANTINE", true));
        assertThat(v.status()).isEqualTo("REFUNDED");
        assertThat(v.refundChannel()).isEqualTo("CASH_AT_PICKUP");
        verify(payments, never()).refundReturn(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("[MKT-R13.2] only an approved return can be received, and only by its own seller")
    void receivedGuards() {
        Placed p = delivered(ali, "COD");
        String no = returnOf(p, "DEFECTIVE").returns().get(0).returnNo();
        assertThatThrownBy(() -> svc.received(new SupportDTOs.ReceivedRequest(no, "RESTOCK", true))).hasMessageContaining("Only an approved return");
        svc.decide(new SupportDTOs.DecisionRequest(no, "APPROVED", null));
        when(access.org()).thenReturn(OTHER_SELLER);
        assertThatThrownBy(() -> svc.received(new SupportDTOs.ReceivedRequest(no, "RESTOCK", true))).hasMessage("No such return.");
        when(access.org()).thenReturn(SELLER);
        assertThatThrownBy(() -> svc.received(new SupportDTOs.ReceivedRequest(no, "BURN", true))).hasMessageContaining("restock, quarantine or write off");
    }

    @Test
    @DisplayName("[MKT-R22.4] a decision is audited once, under the SELLER; a refused decision is never recorded")
    void decisionAudited() {
        Placed p = delivered(ali, "COD");
        String no = returnOf(p, "DEFECTIVE").returns().get(0).returnNo();
        assertThatThrownBy(() -> svc.decide(new SupportDTOs.DecisionRequest(no, "MAYBE", null))).hasMessage("Choose Approve or Reject.");
        svc.decide(new SupportDTOs.DecisionRequest(no, "APPROVED", null));
        svc.decide(new SupportDTOs.DecisionRequest(no, "APPROVED", null));                            // asked again: nothing new
        verify(audit, times(1)).event(eq(MarketplaceAuditService.RETURN_DECIDED), anyString(), eq(no), eq(SELLER),
                eq(MarketplaceAuditService.Actor.OPERATOR), eq("REQUESTED"), eq("APPROVED"), any(), any());
        assertThatThrownBy(() -> svc.decide(new SupportDTOs.DecisionRequest(no, "REJECTED", "late"))).hasMessageContaining("already approved");
    }

    // ── helpers ──

    static MarketplaceCustomer customer(long id) {
        MarketplaceCustomer c = new MarketplaceCustomer();
        c.setId(id);
        c.setPhone("0300000000" + id);
        c.setName("c" + id);
        c.setPasswordHash("x");
        return c;
    }

    @SuppressWarnings("unchecked")
    <T> T put(Map<Long, T> table, T e) {
        try {
            if (e.getClass().getMethod("getId").invoke(e) == null) e.getClass().getMethod("setId", Long.class).invoke(e, ids.incrementAndGet());
            table.put((Long) e.getClass().getMethod("getId").invoke(e), e);
            return e;
        } catch (ReflectiveOperationException x) {
            throw new IllegalStateException(x);
        }
    }
}
