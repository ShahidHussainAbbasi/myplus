package com.myplus.education.service;

import com.myplus.common.subledger.AgingCalculator;
import com.myplus.common.subledger.AgingCalculator.AgingRow;
import com.myplus.common.subledger.OpenDoc;
import com.myplus.common.subledger.PartyAgingDTO;
import com.myplus.common.subledger.SettleOutcome;
import com.myplus.common.subledger.StatementBuilder;
import com.myplus.common.subledger.StatementLine;
import com.myplus.common.subledger.SubledgerService;
import com.myplus.education.entity.FeeCollection;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.FeeCollectionRepository;
import com.myplus.education.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slice 0.2a — a student's fee receivables: settlement, aging and statement.
 *
 * Deliberately thin. The FIFO allocation, the aging buckets and the running-balance statement are the SHARED
 * {@link SubledgerService} / {@link AgingCalculator} / {@link StatementBuilder} from common-subledger — the same
 * code that settles a POS customer's invoices and a vendor's bills. This class only adapts education's data to
 * those contracts: it says what a student's open documents are, and what counts as a charge versus a payment.
 *
 * There is no new table. A {@link FeeCollection} row with a positive {@code dueBalance} IS an open receivable.
 */
@Service
@RequiredArgsConstructor
public class FeeArrearsService {

    private final FeeCollectionRepository feeCollectionRepository;
    private final StudentRepository studentRepository;
    private final SubledgerService subledgerService;

    private static BigDecimal bd(Integer v) { return v == null ? BigDecimal.ZERO : BigDecimal.valueOf(v); }

    /**
     * A student's still-owing fee rows, OLDEST FIRST — the order matters, because FIFO allocation is what makes a
     * statement meaningful ("this payment cleared March, then part of April").
     */
    @Transactional(readOnly = true)
    public List<FeeCollection> openRows(Long orgId, String enrollNo) {
        return feeCollectionRepository.findByOrganizationIdAndEnrollNoOrderByIdAsc(orgId, enrollNo).stream()
                .filter(f -> f.getDueBalance() != null && f.getDueBalance() > 0)
                .sorted(Comparator.comparing(FeeCollection::getPaymentDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Adapt a fee row to the shared subledger's open-document contract. */
    private OpenDoc asOpenDoc(FeeCollection f) {
        return new OpenDoc() {
            public BigDecimal outstanding() { return bd(f.getDueBalance()); }
            public void apply(BigDecimal amount) {
                // Education money is whole currency units (see Grade.fee) — intValue is exact here, not lossy.
                int applied = amount.intValue();
                f.setFeePaid((f.getFeePaid() == null ? 0 : f.getFeePaid()) + applied);
                f.setDueBalance(Math.max((f.getDueBalance() == null ? 0 : f.getDueBalance()) - applied, 0));
                feeCollectionRepository.save(f);
            }
            public String docType() { return "FEE"; }
            public Long docId() { return f.getId(); }
            public String docNo() { return String.valueOf(f.getId()); }
        };
    }

    /**
     * Settle a fee payment across the student's outstanding dues, oldest first, through the shared subledger —
     * the identical path a POS customer receipt takes, so the ledger sees one kind of receipt.
     *
     * @return what was allocated, what is left unallocated, and the student's new balance
     */
    @Transactional
    public SettleOutcome settle(Long orgId, String enrollNo, String studentName, Long studentId,
                                BigDecimal amount, String method, LocalDate paidOn, String reference) {
        List<FeeCollection> rows = openRows(orgId, enrollNo);
        List<OpenDoc> docs = new ArrayList<>();
        for (FeeCollection f : rows) docs.add(asOpenDoc(f));

        return subledgerService.settle("RECEIPT", "STUDENT", studentId, studentName,
                amount, method, paidOn, reference, "education", docs,
                () -> totalOutstanding(orgId, enrollNo));
    }

    /**
     * Slice 0.2b: apply CREDIT the school already holds to a student's open dues, oldest first.
     *
     * Uses the shared allocator but NOT the settle path: spending credit moves a liability, it is not a cash
     * receipt, so recording a second Payment here would count the same money as received twice — once when the
     * guardian overpaid, once when the credit was used.
     *
     * @return how much credit was actually absorbed by open dues (may be less than offered)
     */
    @Transactional
    public BigDecimal applyCreditToDues(Long orgId, String enrollNo, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return BigDecimal.ZERO;
        List<OpenDoc> docs = new ArrayList<>();
        for (FeeCollection f : openRows(orgId, enrollNo)) docs.add(asOpenDoc(f));
        return subledgerService.allocate(docs, amount).stream()
                .map(com.myplus.commerce.contracts.dto.PaymentAllocationRef::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The student's total still-owing amount, recomputed from the rows after allocation. */
    @Transactional(readOnly = true)
    public BigDecimal totalOutstanding(Long orgId, String enrollNo) {
        return feeCollectionRepository.findByOrganizationIdAndEnrollNoOrderByIdAsc(orgId, enrollNo).stream()
                .map(f -> bd(f.getDueBalance()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Aging across every student in the org, bucketed 0–30 / 31–60 / 61–90 / 90+ by the SHARED calculator — so a
     * school's arrears are computed by exactly the same code as a shop's receivables. Replaces the bespoke
     * arrears arithmetic that used to live in the UI layer.
     */
    @Transactional(readOnly = true)
    public List<PartyAgingDTO> aging(Long orgId, Long userId, LocalDate asOf) {
        // One pass over the org's fee rows, grouped by student — not a query per student (performance standard).
        Map<String, List<AgingRow>> byEnroll = new LinkedHashMap<>();
        for (FeeCollection f : feeCollectionRepository.findScoped(orgId, userId)) {
            if (f.getDueBalance() == null || f.getDueBalance() <= 0) continue;
            byEnroll.computeIfAbsent(f.getEnrollNo(), k -> new ArrayList<>())
                    .add(new AgingRow(bd(f.getDueBalance()),
                            f.getPaymentDate() != null ? f.getPaymentDate() : asOf));
        }

        Map<String, Student> students = new LinkedHashMap<>();
        for (Student s : studentRepository.findScoped(orgId, userId))
            if (s.getEnrollNo() != null) students.putIfAbsent(s.getEnrollNo(), s);

        List<PartyAgingDTO> out = new ArrayList<>();
        for (Map.Entry<String, List<AgingRow>> e : byEnroll.entrySet()) {
            BigDecimal[] b = AgingCalculator.bucketize(e.getValue(), asOf);
            Student s = students.get(e.getKey());
            out.add(new PartyAgingDTO(
                    s != null ? s.getId() : null,
                    (s != null ? s.getName() : e.getKey()) + " (" + e.getKey() + ")",
                    b[0], b[1], b[2], b[3], b[0].add(b[1]).add(b[2]).add(b[3])));
        }
        return out;
    }

    /**
     * A student's statement: every charge and payment in order, with a running balance — built by the SHARED
     * {@link StatementBuilder}, the same one behind a POS customer statement.
     *
     * Each fee row contributes a CHARGE line for what it billed and a PAYMENT line for what it settled.
     *
     * The payment line uses the row's OWN {@code dueAmount − dueBalance}, not its {@code feePaid}. That
     * distinction is not cosmetic: FIFO settlement bumps {@code feePaid} on the OLDER rows a payment cleared, so
     * summing {@code feePaid} would count one payment on the row that took the money AND again on every row it
     * settled — a statement that understates what a guardian owes. Deriving from the row's own charge and remaining
     * balance counts each rupee exactly once, on the row it was actually applied to.
     */
    @Transactional(readOnly = true)
    public List<StatementLine> statement(Long orgId, String enrollNo) {
        List<StatementLine> lines = new ArrayList<>();
        for (FeeCollection f : feeCollectionRepository.findByOrganizationIdAndEnrollNoOrderByIdAsc(orgId, enrollNo)) {
            LocalDate d = f.getPaymentDate();
            String no = String.valueOf(f.getId());
            int charged = f.getDueAmount() == null ? 0 : f.getDueAmount();
            int balance = f.getDueBalance() == null ? 0 : f.getDueBalance();
            int settled = Math.max(charged - balance, 0);   // what THIS row's charge has had applied to it

            if (charged > 0)
                lines.add(new StatementLine(d, no, "FEE_CHARGE", bd(charged), BigDecimal.ZERO, null));
            if (settled > 0)
                lines.add(new StatementLine(d, no, "PAYMENT", BigDecimal.ZERO, bd(settled), null));
        }
        return StatementBuilder.build(lines, BigDecimal.ZERO);
    }
}
