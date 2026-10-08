package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.LedgerEntryType;
import com.myplus.marketplace.multiseller.dto.SettlementDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSettlementEntryRepository;

/** MKT-2f — the settlement report: every ledger type in one column, the columns add up, the period and who may read it. */
@ExtendWith(MockitoExtension.class)
class SettlementReportServiceTest {

    @Mock MarketplaceSettlementEntryRepository entries;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock SellerAccess access;
    SettlementReportService service;

    @BeforeEach
    void wire() {
        service = new SettlementReportService(entries, accounts, access);
        lenient().when(accounts.findByOrganizationId(any())).thenAnswer(i -> {
            MarketplaceSellerAccount a = new MarketplaceSellerAccount();
            a.setDisplayName((Long) i.getArgument(0) == 7L ? "Shahzad Mobile Shop" : "Mobile Distributor");
            return Optional.of(a);
        });
    }

    static Object[] t(long org, LedgerEntryType type, String debit, String credit, long n) {
        return new Object[] { org, type.name(), new BigDecimal(debit), new BigDecimal(credit), n };
    }

    /** Seller 7: two card sales, a COD sale, a refund, a payout. Seller 8: a COD sale and its payment. */
    static List<Object[]> period() {
        List<Object[]> r = new ArrayList<>();
        r.add(t(7, LedgerEntryType.SALE, "0", "157000.00", 3));
        r.add(t(7, LedgerEntryType.COMMISSION, "12560.00", "0", 3));
        r.add(t(7, LedgerEntryType.DELIVERY_FEE, "300.00", "0", 1));
        r.add(t(7, LedgerEntryType.TAX, "100.00", "0", 1));
        r.add(t(7, LedgerEntryType.REFUND, "5000.00", "0", 1));
        r.add(t(7, LedgerEntryType.COLLECTED_BY_SELLER, "52000.00", "0", 1));
        r.add(t(7, LedgerEntryType.PAYOUT, "60000.00", "0", 1));
        r.add(t(8, LedgerEntryType.SALE, "0", "52000.00", 1));
        r.add(t(8, LedgerEntryType.COMMISSION, "4160.00", "0", 1));
        r.add(t(8, LedgerEntryType.COLLECTED_BY_SELLER, "52000.00", "0", 1));
        r.add(t(8, LedgerEntryType.REMITTANCE, "0", "4160.00", 1));
        r.add(t(8, LedgerEntryType.ADJUSTMENT, "0", "10.00", 1));
        return r;
    }

    @Test
    @DisplayName("[MKT-R20.3] every ledger type lands in exactly one column, so the columns can add up")
    void everyTypeHasAColumn() {
        for (LedgerEntryType type : LedgerEntryType.values())
            assertThat(SettlementReportService.COLUMN).as(type.name()).containsKey(type);
    }

    @Test
    @DisplayName("[MKT-R20.3] [MKT-R15.5] one row per seller, signed from the seller's side; opening + the columns = closing")
    void rowsAddUp() {
        List<Object[]> openings = List.<Object[]>of(new Object[] { 7L, new BigDecimal("1000.00") });
        List<SettlementDTOs.ReportRow> rows = SettlementReportService.rows(openings, period(), null, org -> org == 7L ? "Shahzad Mobile Shop" : "Mobile Distributor");
        assertThat(rows).extracting(SettlementDTOs.ReportRow::organizationId).containsExactly(8L, 7L);    // by name
        SettlementDTOs.ReportRow a = rows.get(1);
        assertThat(a.opening()).isEqualByComparingTo("1000.00");
        assertThat(a.sales()).isEqualByComparingTo("157000.00");
        assertThat(a.commission()).isEqualByComparingTo("-12560.00");
        assertThat(a.feesAndTax()).isEqualByComparingTo("-400.00");
        assertThat(a.refunds()).isEqualByComparingTo("-5000.00");
        assertThat(a.collectedBySeller()).isEqualByComparingTo("-52000.00");
        assertThat(a.paidOut()).isEqualByComparingTo("-60000.00");
        assertThat(a.lines()).isEqualTo(3);
        assertThat(a.closing()).isEqualByComparingTo("28040.00");
        SettlementDTOs.ReportRow b = rows.get(0);
        assertThat(b.opening()).isZero();
        assertThat(b.remitted()).isEqualByComparingTo("4160.00");
        assertThat(b.corrections()).isEqualByComparingTo("10.00");
        assertThat(b.closing()).as("the cash order paid for: only the correction is left").isEqualByComparingTo("10.00");
        for (SettlementDTOs.ReportRow r : rows) {
            BigDecimal sum = r.opening().add(r.sales()).add(r.commission()).add(r.feesAndTax()).add(r.reserve()).add(r.refunds())
                    .add(r.corrections()).add(r.collectedBySeller()).add(r.remitted()).add(r.paidOut());
            assertThat(sum).as("adds up").isEqualByComparingTo(r.closing());
        }
        SettlementDTOs.ReportRow tot = SettlementReportService.total(rows);
        assertThat(tot.closing()).isEqualByComparingTo("28050.00");
        assertThat(tot.lines()).isEqualTo(4);
    }

    @Test
    @DisplayName("[MKT-R20.3] a seller with only an opening balance still has its row: nothing moved, closing = opening")
    void openingOnly() {
        List<SettlementDTOs.ReportRow> rows = SettlementReportService.rows(List.<Object[]>of(new Object[] { 9L, new BigDecimal("-50.00") }),
                List.of(), null, org -> "Quiet Shop");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).closing()).isEqualByComparingTo("-50.00");
        assertThat(rows.get(0).sales()).isZero();
    }

    @Test
    @DisplayName("[MKT-R20.3] the period is read as whole days: from 00:00 to the day after the end; this month by default")
    void period_() {
        when(entries.balancesBefore(any())).thenReturn(List.of());
        when(entries.periodTotals(any(), any())).thenReturn(period());
        SettlementDTOs.SettlementReport r = service.operator(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        ArgumentCaptor<LocalDateTime> f = ArgumentCaptor.forClass(LocalDateTime.class), t = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(entries).periodTotals(f.capture(), t.capture());
        assertThat(f.getValue()).isEqualTo(SettlementReportService.startOf(LocalDate.of(2026, 10, 1)));
        assertThat(t.getValue()).as("to the end of the last day").isEqualTo(SettlementReportService.startOf(LocalDate.of(2026, 11, 1)));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.totals().sales()).isEqualByComparingTo("209000.00");
        SettlementDTOs.SettlementReport d = service.operator(null, null);
        assertThat(d.from().getDayOfMonth()).isEqualTo(1);
        assertThat(d.to()).isAfterOrEqualTo(d.from());
    }

    @Test
    @DisplayName("[MKT-R20.3] a day is the tenant's day: the ledger is written in UTC, so 1 October in Pakistan starts at 19:00 on 30 September")
    void tenantDay() {
        java.time.ZoneId was = TenantClock.zone();
        try {
            TenantClock.configureDefaultZone("Asia/Karachi");
            assertThat(SettlementReportService.startOf(LocalDate.of(2026, 10, 1))).isEqualTo(LocalDateTime.of(2026, 9, 30, 19, 0));
        } finally {
            TenantClock.configureDefaultZone(was.getId());
        }
    }

    @Test
    @DisplayName("[MKT-R20.3] a period backwards, or longer than 366 days, is refused before reading")
    void periodRefused() {
        assertThatThrownBy(() -> service.operator(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ValidationException.class).hasMessage("The start of the period is after its end.");
        assertThatThrownBy(() -> service.operator(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)))
                .isInstanceOf(ValidationException.class).hasMessage("Choose a period of at most 366 days.");
        assertThat(service.operator(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)).rows()).as("exactly 366 days").isEmpty();
    }

    @Test
    @DisplayName("[MKT-R20.3] [MKT-R22.1] the operator's report is the operator's; a seller reads only its own row")
    void access() {
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> service.operator(null, null)).isInstanceOf(AccessDeniedException.class);
        verify(entries, never()).periodTotals(any(), any());
        when(access.org()).thenReturn(8L);
        when(entries.balancesBefore(any())).thenReturn(List.of());
        when(entries.periodTotals(any(), any())).thenReturn(period());
        SettlementDTOs.SettlementReport mine = service.mine(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(mine.rows()).extracting(SettlementDTOs.ReportRow::organizationId).containsExactly(8L);
        assertThat(mine.totals().sales()).as("the totals are its own").isEqualByComparingTo("52000.00");
        when(access.org()).thenReturn(99L);
        SettlementDTOs.SettlementReport none = service.mine(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(none.rows()).as("a seller with no ledger gets a zero row").hasSize(1);
        assertThat(none.rows().get(0).closing()).isZero();
    }
}
