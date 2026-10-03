package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Purchase;

/** An opening balance is named for what it is on BOTH statements — customer (L18) and, since 2026-10-03, supplier. */
class StatementLineTypeTest {

    @Test
    @DisplayName("a supplier opening balance is OPENING, an ordinary bill stays BILL")
    void supplier_side() {
        Purchase opening = new Purchase(); opening.setDocType(OpeningBalanceService.DOC_OPENING);
        Purchase bill = new Purchase(); bill.setDocType(OpeningBalanceService.DOC_SALE);
        assertThat(FinanceReportService.creditLineType(opening)).isEqualTo("OPENING");
        assertThat(FinanceReportService.creditLineType(bill)).isEqualTo("BILL");
    }

    @Test
    @DisplayName("the customer side is unchanged")
    void customer_side() {
        CustomerHistory opening = new CustomerHistory(); opening.setDocType(OpeningBalanceService.DOC_OPENING);
        assertThat(FinanceReportService.debitLineType(opening)).isEqualTo("OPENING");
    }
}
