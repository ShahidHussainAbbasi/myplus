package com.myplus.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.expense.dto.ExpenseDtos.LineRequest;
import com.myplus.expense.dto.ExpenseDtos.VoucherRequest;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseBillPaymentRepo;
import com.myplus.expense.repository.ExpenseVoucherRepo;

/** EX-8a — the report on the P&L's rule, its groupings and CSV; and E8 — no branch from the user path. */
class ExpenseReportServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 2), D9 = LocalDate.of(2026, 10, 9);

    private final ExpenseVoucherRepo repo = mock(ExpenseVoucherRepo.class);
    private final ExpenseAccess access = mock(ExpenseAccess.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private ExpenseReportService service;

    @BeforeEach
    void setUp() {
        when(access.org()).thenReturn(7L);
        when(access.visibleUserId()).thenReturn(null);
        when(access.canApprove()).thenReturn(true);
        when(staff.staff()).thenReturn(List.of(new StaffDirectory.Member(3L, "User Education", "u@x", "USER")));
        service = new ExpenseReportService(repo, access, staff);
    }

    private static ExpenseVoucher voucher(String no, LocalDate on, String paidFrom, Long userId, Object... lines) {
        ExpenseVoucher v = new ExpenseVoucher();
        v.setVoucherNo(no);
        v.setVoucherDate(on);
        v.setPaidFrom(paidFrom);
        v.setUserId(userId);
        v.setPayeeName("Payee " + no);
        for (int i = 0; i < lines.length; i += 3) {
            ExpenseVoucherLine l = new ExpenseVoucherLine();
            l.setCategoryName((String) lines[i]);
            l.setAccountCode((String) lines[i + 1]);
            l.setAmount(new BigDecimal((String) lines[i + 2]));
            v.addLine(l);
        }
        return v;
    }

    @Test
    @DisplayName("⭐ the P&L's rule: in the books on its own date; a void is a NEGATIVE on its reversal's date")
    void reconcilesWithThePnl() {
        ExpenseVoucher rent = voucher("EXP-1", D1, "CASH", 3L, "Rent", "6000", "40");
        ExpenseVoucher fuel = voucher("EXP-2", D1, "BANK", 3L, "Fuel", "6200", "25");
        ExpenseVoucher voided = voucher("EXP-1", D1, "CASH", 3L, "Rent", "6000", "40");
        voided.setVoidPostedOn(D9);
        when(repo.postedInRange(eq(7L), isNull(), any(), any())).thenReturn(List.of(rent, fuel));
        when(repo.voidsInRange(eq(7L), isNull(), any(), any())).thenReturn(List.of(voided));

        var s = service.summary(D1, D9, "category");

        assertThat(s.total()).isEqualByComparingTo("25");                        // 40 + 25 − 40
        assertThat(s.groups()).extracting(ExpenseReportService.Group::label).contains("Rent (6000)", "Fuel (6200)");
        assertThat(s.groups().stream().filter(g -> g.label().startsWith("Rent")).findFirst().orElseThrow().amount())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("every grouping adds up to the same total; member names come from the staff list; month sorts by month")
    void groupingsAgree() {
        when(repo.postedInRange(eq(7L), isNull(), any(), any())).thenReturn(List.of(
                voucher("EXP-1", D1, "CASH", 3L, "Rent", "6000", "40", "Fuel", "6200", "5"),
                voucher("EXP-2", LocalDate.of(2026, 9, 30), "EMPLOYEE", 9L, "Fuel", "6200", "10")));
        when(repo.voidsInRange(any(), any(), any(), any())).thenReturn(List.of());
        for (String by : List.of("category", "member", "month", "paidFrom")) {
            var s = service.summary(LocalDate.of(2026, 9, 1), D9, by);
            assertThat(s.total()).as(by).isEqualByComparingTo("55");
            assertThat(s.groups().stream().map(ExpenseReportService.Group::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as(by).isEqualByComparingTo("55");
        }
        assertThat(service.summary(LocalDate.of(2026, 9, 1), D9, "member").groups())
                .extracting(ExpenseReportService.Group::label).containsExactlyInAnyOrder("User Education", "Member #9");
        assertThat(service.summary(LocalDate.of(2026, 9, 1), D9, "month").groups())
                .extracting(ExpenseReportService.Group::label).containsExactly("Sep 2026", "Oct 2026");
        assertThat(service.summary(LocalDate.of(2026, 9, 1), D9, "paidFrom").groups())
                .extracting(ExpenseReportService.Group::label).contains("Cash", "Claim");
    }

    @Test
    @DisplayName("⭐ a user's report is their own (the list's scope) and names them 'You'")
    void userScope() {
        when(access.canApprove()).thenReturn(false);
        when(access.visibleUserId()).thenReturn(3L);
        when(repo.postedInRange(eq(7L), eq(3L), any(), any())).thenReturn(List.of(voucher("EXP-1", D1, "CASH", 3L, "Rent", "6000", "7")));
        when(repo.voidsInRange(eq(7L), eq(3L), any(), any())).thenReturn(List.of());
        var s = service.summary(D1, D9, "member");
        assertThat(s.groups()).extracting(ExpenseReportService.Group::label).containsExactly("You");
        verify(staff, never()).staff();
    }

    @Test
    @DisplayName("the CSV: a row per line, the void a negative row, and it sums to the report; cells are escaped and defused")
    void csv() {
        ExpenseVoucher a = voucher("EXP-1", D1, "CASH", 3L, "Rent", "6000", "40");
        a.setPayeeName("=HYPERLINK(\"x\")");
        ExpenseVoucher v = voucher("EXP-1", D1, "CASH", 3L, "Rent", "6000", "40");
        v.setVoidPostedOn(D9);
        when(repo.postedInRange(any(), any(), any(), any())).thenReturn(List.of(a));
        when(repo.voidsInRange(any(), any(), any(), any())).thenReturn(List.of(v));
        String[] rows = service.csv(D1, D9).split("\r\n");
        assertThat(rows[0]).startsWith("Date,Number,Category");
        assertThat(rows).hasSize(3);
        assertThat(rows[1]).contains("40.00").endsWith("Expense").contains("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(rows[2]).startsWith("2026-10-09").contains("-40.00").endsWith("Void");
        assertThat(ExpenseReportService.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ExpenseReportService.cell("+1")).isEqualTo("'+1");
    }

    @Test
    @DisplayName("a period ending before it starts, or longer than two years, is refused; an unknown grouping too")
    void periods() {
        assertThatThrownBy(() -> ExpenseReportService.period(D9, D1)).hasMessageContaining("before its start");
        assertThatThrownBy(() -> ExpenseReportService.period(LocalDate.of(2023, 1, 1), D9)).hasMessageContaining("at most two years");
        when(repo.postedInRange(any(), any(), any(), any())).thenReturn(List.of());
        when(repo.voidsInRange(any(), any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.summary(D1, D9, "colour")).hasMessageContaining("category, member, month or paid from");
    }

    @Test
    @DisplayName("⭐ E8 — a branch is refused on the user path (it could not be checked); nothing is saved")
    void noBranchFromTheRequest() {
        when(access.userId()).thenReturn(3L);
        when(repo.findByOrganizationIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        ExpenseSettings settings = mock(ExpenseSettings.class);
        when(settings.backdateDays()).thenReturn(30);
        ExpenseVoucherService vouchers = new ExpenseVoucherService(repo, mock(ExpenseCategoryService.class), mock(ExpenseOutboxService.class),
                mock(ExpenseAuditService.class), access, mock(DocumentNumberService.class), mock(ExpenseTagService.class),
                mock(ExpenseBillPaymentRepo.class), settings, mock(ReceiptService.class));
        VoucherRequest r = new VoucherRequest(LocalDate.now(), "CASH", 999999L, null, null,
                List.of(new LineRequest(1L, new BigDecimal("3"), null, null, null)), null, null, null);
        assertThatThrownBy(() -> vouchers.record(r, true, "k")).hasMessageContaining("branch");
        verify(repo, never()).saveAndFlush(any());
    }
}
