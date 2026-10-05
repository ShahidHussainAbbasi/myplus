package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.SellBatch;
import com.myplus.business_service.repository.SellBatchRepo;
import com.myplus.commerce.contracts.dto.StockPick;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PR-3a — a sale line records ITS OWN batches. Matching picks on product alone made two lines of the same product each
 * record both lines' batches (live: invoice 6292 — 2 units sold, 4 recorded), and returns and edits cost from those rows.
 */
@ExtendWith(MockitoExtension.class)
class SaleBatchByLineTest {

    @Mock private ICustomerService customerService;
    @Mock private ICustomerHistoryService customerHistoryService;
    @Mock private ISellService sellService;
    @Mock private PaymentService paymentService;
    @Mock private com.myplus.business_service.repository.CashierShiftRepo cashierShiftRepo;
    @Mock private SellBatchRepo sellBatchRepo;
    @InjectMocks private SagaSaleWriter writer;

    private static final AuthenticatedUser USER = new AuthenticatedUser(1L, "cashier@test.com", List.of(), 7L);

    private static SagaLine line(long productId) {
        SagaLine l = mock(SagaLine.class);
        when(l.productId()).thenReturn(productId);
        return l;
    }

    private static Sell sell(long id) {
        Sell s = new Sell();
        s.setSellId(id);
        return s;
    }

    private static StockPick pick(long productId, String batch, String qty, Integer lineRef) {
        return new StockPick(productId, batch, new BigDecimal(qty), null, new BigDecimal("100"), lineRef);
    }

    @Test
    @DisplayName("two lines of one product: each records only the picks taken for it")
    void each_line_records_its_own_picks() {
        List<StockPick> picks = List.of(pick(50L, "B-0912", "2", 0), pick(50L, "B-1003", "3", 1));

        writer.recordBatches(sell(11L), line(50L), 0, picks, USER);
        writer.recordBatches(sell(12L), line(50L), 1, picks, USER);

        ArgumentCaptor<SellBatch> rows = ArgumentCaptor.forClass(SellBatch.class);
        verify(sellBatchRepo, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(SellBatch::getSellId, SellBatch::getBatchNo)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11L, "B-0912"), org.assertj.core.groups.Tuple.tuple(12L, "B-1003"));
        BigDecimal recorded = rows.getAllValues().stream().map(SellBatch::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(recorded).as("batch rows sum to the 5 units sold, not 10").isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("picks without a line (an inventory before PR-3a) still match by product, as before")
    void line_less_picks_match_by_product() {
        List<StockPick> picks = List.of(pick(50L, "B-0912", "2", null), pick(60L, "B-7", "1", null));

        writer.recordBatches(sell(11L), line(50L), 0, picks, USER);

        verify(sellBatchRepo, times(1)).save(any(SellBatch.class));
    }

    @Test
    @DisplayName("a pick for another product never lands on this line, whatever its line number")
    void other_product_never_recorded() {
        List<StockPick> picks = List.of(pick(60L, "B-7", "1", 0));

        writer.recordBatches(sell(11L), line(50L), 0, picks, USER);

        verify(sellBatchRepo, times(0)).save(any(SellBatch.class));
    }
}
