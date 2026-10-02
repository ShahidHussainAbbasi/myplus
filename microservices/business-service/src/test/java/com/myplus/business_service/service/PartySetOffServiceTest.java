package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.myplus.business_service.client.SetOffLedgerClient;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.PartySetOff;
import com.myplus.business_service.entity.PartySetOffAlloc;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerHistoryRepo;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.PartySetOffAllocRepo;
import com.myplus.business_service.repository.PartySetOffRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.subledger.SubledgerService;
import com.myplus.commerce.contracts.dto.PaymentAllocationRef;

/**
 * DR-4 — set-off, without a database. What a defect here would break: a balance cleared with no journal, a set-off
 * over the smaller side, a contra entry against an installment plan, a double-click that settles twice, a reversal
 * that re-opens the wrong amount.
 */
class PartySetOffServiceTest {

    private CustomerRepo customers;
    private VenderRepo venders;
    private CustomerHistoryRepo invoices;
    private PurchaseRepo bills;
    private PartySetOffRepo setOffs;
    private PartySetOffAllocRepo allocs;
    private ICustomerService customerService;
    private IVenderService venderService;
    private DocumentNumberService docnum;
    private InstallmentPlanService plans;
    private SetOffLedgerClient ledger;
    private PartySetOffService service;

    private CustomerHistory inv1, inv2;
    private Purchase bill;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerRepo.class);
        venders = mock(VenderRepo.class);
        invoices = mock(CustomerHistoryRepo.class);
        bills = mock(PurchaseRepo.class);
        setOffs = mock(PartySetOffRepo.class);
        allocs = mock(PartySetOffAllocRepo.class);
        customerService = mock(ICustomerService.class);
        venderService = mock(IVenderService.class);
        docnum = mock(DocumentNumberService.class);
        plans = mock(InstallmentPlanService.class);
        ledger = mock(SetOffLedgerClient.class);

        service = new PartySetOffService();
        ReflectionTestUtils.setField(service, "customerRepo", customers);
        ReflectionTestUtils.setField(service, "venderRepo", venders);
        ReflectionTestUtils.setField(service, "customerHistoryRepo", invoices);
        ReflectionTestUtils.setField(service, "purchaseRepo", bills);
        ReflectionTestUtils.setField(service, "setOffRepo", setOffs);
        ReflectionTestUtils.setField(service, "allocRepo", allocs);
        ReflectionTestUtils.setField(service, "customerService", customerService);
        ReflectionTestUtils.setField(service, "venderService", venderService);
        ReflectionTestUtils.setField(service, "periodLockGuard", mock(PeriodLockGuard.class));
        ReflectionTestUtils.setField(service, "documentNumberService", docnum);
        ReflectionTestUtils.setField(service, "subledgerService", new SubledgerService());   // the REAL allocator
        ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
        ReflectionTestUtils.setField(service, "installmentPlanService", plans);
        ReflectionTestUtils.setField(service, "ledger", ledger);

        Customer c = new Customer(); c.setCustomerId(1L); c.setPartyId(100L); c.setName("Usman Traders");
        Vender v = new Vender(); v.setId(2L); v.setPartyId(100L); v.setName("Usman & Co");
        when(customers.findByIdScoped(eq(1L), any(), any())).thenReturn(Optional.of(c));
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(v));

        // They owe us 20,000 + 10,000 on two invoices; we owe them 50,000 on one bill.
        inv1 = invoice(11L, "INV-1", "-20000");
        inv2 = invoice(12L, "INV-2", "-10000");
        bill = new Purchase(); bill.setPurchaseId(21L); bill.setPurchaseInvoiceNo("B-1");
        bill.setDueAmount(new BigDecimal("-50000")); bill.setPaidAmount(BigDecimal.ZERO);
        when(invoices.findOpenInvoicesByCustomer(1L)).thenReturn(List.of(inv1, inv2));
        when(bills.findOpenPurchasesByVendor(2L)).thenReturn(List.of(bill));
        when(plans.planInvoiceNumbers(any(), eq(1L))).thenReturn(List.of());

        when(setOffs.findByKey(any(), anyString())).thenReturn(Optional.empty());
        when(setOffs.findByReversalKey(any(), anyString())).thenReturn(Optional.empty());
        when(setOffs.save(any())).thenAnswer(i -> { PartySetOff s = i.getArgument(0); if (s.getId() == null) s.setId(9L); return s; });
        when(docnum.next(any(), eq(DocType.SETOFF))).thenReturn(1L);
        when(ledger.record(any())).thenReturn(new SetOffLedgerClient.Result("RCPT-000007", "PV-000003", false));
        when(ledger.reverse(any())).thenReturn(new SetOffLedgerClient.Result("RCPT-000007", "PV-000003", false));
    }

    private static CustomerHistory invoice(long id, String no, String due) {
        CustomerHistory h = new CustomerHistory();
        h.setCustomer_history_id(id); h.setInvoiceNo(no);
        h.setDueAmount(new BigDecimal(due)); h.setPaidAmount(BigDecimal.ZERO);
        return h;
    }

    private PartySetOffService.Outcome setOff(String amount) {
        return service.setOff(1L, 2L, new BigDecimal(amount), "agreed contra", "letter 12", true, "key-1");
    }

    private static BigDecimal sum(List<PaymentAllocationRef> xs) {
        return xs.stream().map(PaymentAllocationRef::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("refusals: no 'same business' tick, no reason, nothing — and finance is never called")
    void refusals_call_nobody() {
        assertThatThrownBy(() -> service.setOff(1L, 2L, new BigDecimal("100"), "r", null, false, "k"))
                .isInstanceOf(PartyRoleService.Refusal.class).hasMessageContaining("same business");
        assertThatThrownBy(() -> service.setOff(1L, 2L, new BigDecimal("100"), " ", null, true, "k"))
                .isInstanceOf(PartyRoleService.Refusal.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.setOff(1L, 2L, new BigDecimal("100"), "r", null, true, null))
                .isInstanceOf(PartyRoleService.Refusal.class);
        verify(ledger, never()).record(any());
    }

    @Test
    @DisplayName("over the smaller side (30,000 they owe us) is refused and names the limit")
    void over_the_limit_is_refused() {
        assertThatThrownBy(() -> setOff("30000.01")).isInstanceOf(PartyRoleService.Refusal.class).hasMessageContaining("30000");
        verify(ledger, never()).record(any());
    }

    @Test
    @DisplayName("a customer and a supplier of DIFFERENT partners are refused")
    void different_partners_are_refused() {
        Vender other = new Vender(); other.setId(2L); other.setPartyId(200L); other.setName("Someone else");
        when(venders.findByIdScoped(eq(2L), any(), any())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> setOff("100")).isInstanceOf(PartyRoleService.Refusal.class).hasMessageContaining("not the same partner");
    }

    @Test
    @DisplayName("30,000 clears both invoices and 30,000 of the bill; finance gets BOTH legs in one call; SETOFF-000001")
    void set_off_clears_both_sides_and_posts_both_legs_once() {
        var o = setOff("30000");
        assertThat(o.setOffNo()).isEqualTo("SETOFF-000001");
        assertThat(inv1.getDueAmount()).isEqualByComparingTo("0");
        assertThat(inv2.getDueAmount()).isEqualByComparingTo("0");
        assertThat(bill.getDueAmount()).as("we still owe them 20,000").isEqualByComparingTo("-20000");

        ArgumentCaptor<SetOffLedgerClient.Request> req = ArgumentCaptor.forClass(SetOffLedgerClient.Request.class);
        verify(ledger).record(req.capture());
        assertThat(req.getValue().getAmount()).isEqualByComparingTo("30000");
        assertThat(sum(req.getValue().getCustomerAllocations())).isEqualByComparingTo("30000");
        assertThat(sum(req.getValue().getVendorAllocations())).isEqualByComparingTo("30000");
        assertThat(req.getValue().getIdempotencyKey()).isEqualTo("key-1");
        verify(customerService).recomputeDue(any());
        verify(venderService).recomputePayable(2L);
        assertThat(o.receiptNo()).isEqualTo("RCPT-000007");
    }

    @Test
    @DisplayName("finance fails → the exception escapes (the transaction rolls back) and no set-off document is saved")
    void finance_failure_fails_the_set_off() {
        when(ledger.record(any())).thenThrow(new RuntimeException("finance down"));
        assertThatThrownBy(() -> setOff("1000")).hasMessageContaining("finance down");
        verify(setOffs, never()).save(any());
    }

    @Test
    @DisplayName("the same key again answers the first set-off and moves nothing")
    void same_key_replays() {
        PartySetOff first = new PartySetOff();
        first.setId(9L); first.setSetOffNo("SETOFF-000001"); first.setAmount(new BigDecimal("30000"));
        when(setOffs.findByKey(any(), eq("key-1"))).thenReturn(Optional.of(first));
        var o = setOff("30000");
        assertThat(o.replay()).isTrue();
        assertThat(o.setOffNo()).isEqualTo("SETOFF-000001");
        verify(ledger, never()).record(any());
        assertThat(inv1.getDueAmount()).isEqualByComparingTo("-20000");
    }

    @Test
    @DisplayName("an invoice carrying an installment plan is never set off — the limit drops to the ordinary invoice")
    void installment_invoice_is_excluded() {
        when(plans.planInvoiceNumbers(any(), eq(1L))).thenReturn(List.of("INV-1"));
        assertThat(service.settleableReceivable(1L)).isEqualByComparingTo("10000");
        assertThatThrownBy(() -> setOff("10000.01")).isInstanceOf(PartyRoleService.Refusal.class);
        setOff("10000");
        assertThat(inv1.getDueAmount()).as("the plan's invoice is untouched").isEqualByComparingTo("-20000");
        assertThat(inv2.getDueAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("reversal re-opens exactly the recorded amounts, mirrors in finance, and is marked REVERSED")
    void reversal_reopens_exactly_what_was_cleared() {
        PartySetOff s = new PartySetOff();
        s.setId(9L); s.setSetOffNo("SETOFF-000001"); s.setStatus(PartySetOff.POSTED); s.setAmount(new BigDecimal("30000"));
        s.setCustomerId(1L); s.setVenderId(2L); s.setIdempotencyKey("key-1");
        when(setOffs.findScoped(eq(9L), any())).thenReturn(Optional.of(s));
        // As cleared: 20,000 on INV-1 (now 0), 10,000 on INV-2 (now 0), 30,000 on the bill (now -20,000).
        inv1.setDueAmount(BigDecimal.ZERO); inv1.setPaidAmount(new BigDecimal("20000"));
        inv2.setDueAmount(BigDecimal.ZERO); inv2.setPaidAmount(new BigDecimal("10000"));
        bill.setDueAmount(new BigDecimal("-20000")); bill.setPaidAmount(new BigDecimal("30000"));
        when(invoices.findById(11L)).thenReturn(Optional.of(inv1));
        when(invoices.findById(12L)).thenReturn(Optional.of(inv2));
        when(bills.findById(21L)).thenReturn(Optional.of(bill));
        when(allocs.findBySetOff(9L)).thenReturn(List.of(
                alloc("CUSTOMER", 11L, "20000"), alloc("CUSTOMER", 12L, "10000"), alloc("VENDOR", 21L, "30000")));

        service.reverse(9L, "entered twice", "rev-1");
        assertThat(inv1.getDueAmount()).isEqualByComparingTo("-20000");
        assertThat(inv2.getDueAmount()).isEqualByComparingTo("-10000");
        assertThat(bill.getDueAmount()).isEqualByComparingTo("-50000");
        assertThat(inv1.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(s.getStatus()).isEqualTo(PartySetOff.REVERSED);
        verify(ledger).reverse(any());

        // Reversing again is refused — the document is no longer POSTED.
        when(setOffs.findByReversalKey(any(), eq("rev-2"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reverse(9L, "again", "rev-2")).isInstanceOf(PartyRoleService.Refusal.class)
                .hasMessageContaining("already reversed");
    }

    private static PartySetOffAlloc alloc(String side, Long docId, String amount) {
        PartySetOffAlloc a = new PartySetOffAlloc();
        a.setSide(side); a.setDocId(docId); a.setDocType("CUSTOMER".equals(side) ? "INVOICE" : "PURCHASE");
        a.setAmount(new BigDecimal(amount));
        return a;
    }
}
