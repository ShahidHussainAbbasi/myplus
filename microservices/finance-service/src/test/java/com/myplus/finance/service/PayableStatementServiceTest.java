package com.myplus.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.common.subledger.StatementLine;
import com.myplus.finance.entity.PayableDoc;
import com.myplus.finance.entity.PayableNoteRow;
import com.myplus.finance.entity.Payment;

/** FP-4a — the supplier statement served from the subledger reads like business's. */
class PayableStatementServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1), D2 = LocalDate.of(2026, 9, 5), D3 = LocalDate.of(2026, 9, 9);

    private static PayableDoc doc(long id, String source, String no, LocalDate date, String issued, String amount, String status) {
        PayableDoc d = new PayableDoc();
        d.setId(id);
        d.setSource(source);
        d.setDocNo(no);
        d.setDocDate(date);
        d.setIssuedAmount(issued == null ? null : new BigDecimal(issued));
        d.setAmount(new BigDecimal(amount));
        d.setStatus(status);
        return d;
    }

    private static PayableNoteRow note(long docId, String no, LocalDate date, String amount) {
        PayableNoteRow n = new PayableNoteRow();
        n.setPayableDocId(docId);
        n.setNoteNo(no);
        n.setNoteDate(date);
        n.setAmount(new BigDecimal(amount));
        return n;
    }

    private static Payment pay(String no, LocalDate on, String amount, String module, String method, String ref) {
        return Payment.builder().receiptNo(no).paidOn(on).amount(new BigDecimal(amount))
                .sourceModule(module).method(method).reference(ref).build();
    }

    @Test @DisplayName("BILL as issued, its debit note, then payments — sorted by date, running balance")
    void purchaseTrail() {
        List<StatementLine> s = PayableStatementService.build(
                List.of(doc(1, "PURCHASE", "PINV-1", D1, "100", "70", "OPEN")),
                Map.of(1L, List.of(note(1, "DN-1", D2, "30"))),
                List.of(pay("PV-1", D3, "50", "BUSINESS", "CASH", null)), false);
        assertThat(s).extracting(StatementLine::getType).containsExactly("BILL", "DEBIT_NOTE", "PAYMENT");
        assertThat(s.get(0).getDebit()).isEqualByComparingTo("100");
        assertThat(s.get(2).getBalance()).as("100 − 30 − 50").isEqualByComparingTo("20");
    }

    @Test @DisplayName("purchasesOnly = business's view: no expense bills, no payments made from Expenses")
    void purchasesOnly() {
        List<PayableDoc> docs = List.of(doc(1, "PURCHASE", "PINV-1", D1, null, "100", "OPEN"),
                doc(2, "EXPENSE_BILL", "EXP-1", D2, "500", "500", "OPEN"));
        List<Payment> paid = List.of(pay("PV-2", D3, "200", "EXPENSE", "CASH", "EXPB-6-1"),
                pay("PV-1", D3, "40", "BUSINESS", "CASH", null));
        assertThat(PayableStatementService.build(docs, Map.of(), paid, true))
                .extracting(StatementLine::getDocNo).containsExactly("PINV-1", "PV-1");
        List<StatementLine> all = PayableStatementService.build(docs, Map.of(), paid, false);
        assertThat(all).extracting(StatementLine::getDocNo).containsExactly("PINV-1", "EXP-1", "PV-2", "PV-1");
        assertThat(all.get(all.size() - 1).getBalance()).as("100 + 500 − 200 − 40").isEqualByComparingTo("360");
    }

    @Test @DisplayName("a voided purchase stays (its debit note nets it); a voided expense bill is left off")
    void voids() {
        List<StatementLine> s = PayableStatementService.build(
                List.of(doc(1, "PURCHASE", "PINV-1", D1, "100", "0", "VOID"),
                        doc(2, "EXPENSE_BILL", "EXP-9", D1, "70", "70", "VOID")),
                Map.of(1L, List.of(note(1, "DN-1", D1, "100"))), List.of(), false);
        assertThat(s).extracting(StatementLine::getDocNo).containsExactly("PINV-1", "DN-1");
        assertThat(s.get(1).getBalance()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("a set-off payment is named by its document, as business names it (DR-4)")
    void setOffNaming() {
        List<StatementLine> s = PayableStatementService.build(List.of(), Map.of(),
                List.of(pay("RCPT-7", D1, "25", "BUSINESS", "SETOFF", "SETOFF-000001")), false);
        assertThat(s.get(0).getDocNo()).isEqualTo("SETOFF-000001 (RCPT-7)");
    }

    @Test @DisplayName("FP-4b aging: open docs by due date (else doc date), advances apart, biggest first")
    @SuppressWarnings("unchecked")
    void aging() {
        LocalDate asOf = LocalDate.of(2026, 10, 3);
        PayableDoc old = doc(1, "PURCHASE", "P1", LocalDate.of(2026, 7, 1), null, "100", "OPEN");
        old.setPartyId(7L);
        PayableDoc bill = doc(2, "EXPENSE_BILL", "EXP-1", LocalDate.of(2026, 7, 1), "50", "50", "OPEN");
        bill.setPartyId(7L);
        bill.setDueDate(LocalDate.of(2026, 9, 30));         // due 3 days ago: current, though dated in July
        Map<String, Object> out = PayableStatementService.aging(List.of(old, bill),
                List.<Object[]>of(new Object[] { 7L, "A", new BigDecimal("150") }, new Object[] { 9L, "B", new BigDecimal("-40") }),
                asOf);
        var rows = (List<com.myplus.common.subledger.PartyAgingDTO>) out.get("rows");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getB90plus()).as("94 days").isEqualByComparingTo("100");
            assertThat(r.getB0_30()).as("due date, not bill date").isEqualByComparingTo("50");
            assertThat(r.getTotal()).isEqualByComparingTo("150");
        });
        var adv = (List<Map<String, Object>>) out.get("advances");
        assertThat(adv).singleElement().satisfies(a -> assertThat((BigDecimal) a.get("advance")).isEqualByComparingTo("40"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a supplier opening balance (OB- series) is OPENING, the same name business gives it; a bill stays BILL")
    void opening_balance_is_named() {
        com.myplus.finance.entity.PayableDoc ob = new com.myplus.finance.entity.PayableDoc();
        ob.setSource("PURCHASE"); ob.setDocNo("OB-000335");
        com.myplus.finance.entity.PayableDoc bill = new com.myplus.finance.entity.PayableDoc();
        bill.setSource("PURCHASE"); bill.setDocNo("INV-7781");
        com.myplus.finance.entity.PayableDoc expense = new com.myplus.finance.entity.PayableDoc();
        expense.setSource("EXPENSE"); expense.setDocNo("OB-000001");
        org.assertj.core.api.Assertions.assertThat(PayableStatementService.lineType(ob)).isEqualTo("OPENING");
        org.assertj.core.api.Assertions.assertThat(PayableStatementService.lineType(bill)).isEqualTo("BILL");
        org.assertj.core.api.Assertions.assertThat(PayableStatementService.lineType(expense)).as("only a PURCHASE can be an opening balance").isEqualTo("BILL");
    }
}
