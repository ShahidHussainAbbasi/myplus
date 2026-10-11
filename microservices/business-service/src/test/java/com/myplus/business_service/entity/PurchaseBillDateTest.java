package com.myplus.business_service.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.common.security.time.TenantClock;

/**
 * H6 C3b — a purchase's bill date is a calendar day, stamped once at the first save, and every accounting reader takes
 * it via billDay(). The defect it closes: dated.toLocalDate() was the UTC day, so a bill recorded at 02:00 Karachi was
 * dated the day before in the payables document sent to finance, aging, statements and the period lock.
 */
class PurchaseBillDateTest {

    @BeforeEach
    void karachi() { TenantClock.configureDefaultZone("Asia/Karachi"); }

    @Test
    void a_purchase_recorded_at_02_00_karachi_is_billed_on_that_karachi_day() {
        Purchase p = new Purchase();
        p.setDated(LocalDateTime.of(2026, 10, 10, 21, 0));   // 02:00 on the 11th, Karachi
        p.stampBillDate();
        assertThat(p.getBillDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(p.billDay()).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void a_typed_bill_date_keeps_the_day_that_was_typed() {
        Purchase p = new Purchase();
        p.setDated(LocalDateTime.of(2026, 10, 1, 0, 0));      // a typed 01-10-2026, held as UTC midnight
        p.stampBillDate();
        assertThat(p.getBillDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void a_bill_date_already_set_is_never_overwritten() {
        Purchase p = new Purchase();
        p.setDated(LocalDateTime.of(2026, 10, 10, 21, 0));
        p.setBillDate(LocalDate.of(2026, 9, 30));
        p.stampBillDate();
        assertThat(p.getBillDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void a_row_saved_before_v81_still_reads_its_business_day() {
        Purchase p = new Purchase();                           // bill_date NULL: billDay falls back to dated
        p.setDated(LocalDateTime.of(2026, 10, 10, 21, 0));
        assertThat(p.billDay()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(new Purchase().billDay()).isNull();
    }
}
