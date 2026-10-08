package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockPick;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * PR-3c — in Per batch mode a sale is priced from the batches it takes, split where they differ, and reserved PINNED.
 *
 * <p>The scenario of the analysis: abc-123 bought at 200 (7 left, batch B-0912) and later at 250 (batch B-1003). A
 * customer takes 10: the invoice is 7 @ 200 + 3 @ 250, and exactly those batches are held.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PerBatchSaleTest {

    private static final long ABC = 123L;
    private static final long OLD = 9001L, NEW = 9002L;   // stock_entries ids

    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private SagaSaleWriter saleWriter;
    @Mock private RequestUtil requestUtil;
    @Mock private TaxService taxService;
    @Mock private SaleCosting saleCosting;
    @Mock private com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;
    @Mock private com.myplus.business_service.repository.PurchaseRepo purchaseRepo;
    @Mock private com.myplus.common.settings.SettingsService settingsService;
    @Mock private com.myplus.business_service.service.GlOutboxService glOutboxService;
    @Mock private com.myplus.business_service.service.AuditService auditService;
    @Mock private PeriodLockGuard periodLockGuard;
    @Mock private StoreCreditService storeCreditService;
    @Mock private com.myplus.business_service.repository.CustomerRepo customerRepo;
    @Mock private CreditStandingService creditStandingService;
    @Mock private SerialUnitService serialUnitService;
    @Mock private com.myplus.common.settings.CapabilityService capabilityService;
    @Mock private com.myplus.business_service.repository.SellRepo sellRepo;

    @InjectMocks private SagaSellService service;

    private String mode = "per_batch";

    @BeforeEach
    void wire() {
        var set = (java.util.function.BiConsumer<String, Object>) (name, value) ->
                org.springframework.test.util.ReflectionTestUtils.setField(service, name, value);
        set.accept("periodLockGuard", periodLockGuard);
        set.accept("storeCreditService", storeCreditService);
        set.accept("glOutboxService", glOutboxService);
        set.accept("auditService", auditService);
        set.accept("settingsService", settingsService);
        set.accept("customerRepo", customerRepo);
        set.accept("creditStandingService", creditStandingService);
        set.accept("serialUnitService", serialUnitService);
        set.accept("capabilityService", capabilityService);
        set.accept("saleCosting", saleCosting);
        set.accept("sellRepo", sellRepo);

        when(requestUtil.getCurrentUser()).thenReturn(new AuthenticatedUser(1L, "cashier@test.com", List.of(), 1L));
        when(taxService.settingsFor(anyLong())).thenReturn(TaxSetting.builder().enabled(false).build());
        when(taxService.taxForLine(any(), any(), any())).thenAnswer(i -> {
            BigDecimal base = i.getArgument(0);
            return new TaxResult(base, BigDecimal.ZERO, BigDecimal.ZERO, base);
        });
        when(settingsService.getChoice(anyString(), any(), anyString())).thenAnswer(i ->
                PurchaseService.PRICE_MODE_KEY.equals(i.getArgument(0)) ? mode : i.getArgument(2));
        when(settingsService.getDecimal(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(serialUnitService.validateForSale(any(), Mockito.anyBoolean(), Mockito.anyFloat(), any(), any())).thenReturn(List.of());
        when(catalogClient.getProduct(ABC)).thenReturn(ProductRef.builder().id(ABC).name("abc-123")
                .sellingPrice(new BigDecimal("250.00")).build());
        when(purchaseRepo.findRecentCosts(anyLong(), anyLong(), anyLong(), any())).thenReturn(List.of(new BigDecimal("210")));
        CustomerHistory ch = new CustomerHistory();
        ch.setCustomer_history_id(900L);
        ch.setInvoiceNo("INV-000900");
        when(saleWriter.writePending(any(), anyString(), anyString(), any(), anyList(), anyList())).thenReturn(ch);
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
    }

    private static StockPick pick(long entry, String batch, String qty, String price, String cost, int line) {
        return new StockPick(ABC, batch, new BigDecimal(qty), null, new BigDecimal(cost), line, entry,
                price == null ? null : new BigDecimal(price));
    }

    private void plan(StockPick... picks) {
        when(inventoryClient.plan(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse(null, ReservationStatus.PLANNED, List.of(picks), null));
    }

    /** The till's line: the box holds the rate the till put there (autoRate) unless the cashier typed over it. */
    private static SellDTO line(float qty, String boxRate, String autoRate) {
        SellDTO s = new SellDTO();
        s.setProductId(ABC);
        s.setQuantity(qty);
        if (boxRate != null) s.setSellRate(new BigDecimal(boxRate));
        if (autoRate != null) s.setAutoRate(new BigDecimal(autoRate));
        return s;
    }

    private static CustomerHistoryDTO sale(SellDTO... lines) {
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setSales(new ArrayList<>(List.of(lines)));
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<SagaLine> writtenLines() {
        ArgumentCaptor<List<SagaLine>> c = ArgumentCaptor.forClass(List.class);
        verify(saleWriter).writePending(any(), anyString(), anyString(), any(), c.capture(), anyList());
        return c.getValue();
    }

    private List<StockReservationLine> reserved() {
        ArgumentCaptor<StockReservationRequest> c = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(inventoryClient).reserve(c.capture());
        return c.getValue().getLines();
    }

    @Test
    @DisplayName("⭐⭐ 10 units across two batches: 7 @ 200 + 3 @ 250, each batch pinned to its own line")
    void splitsAtTheBatchPrice() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0), pick(NEW, "B-1003", "3", "250", "210", 0));

        service.addSell(sale(line(10f, "250", "250")));

        List<SagaLine> lines = writtenLines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).quantity()).isEqualTo(7f);
        assertThat(lines.get(0).sellRate()).isEqualByComparingTo("200");
        assertThat(lines.get(0).totalAmount()).isEqualByComparingTo("1400.00");
        assertThat(lines.get(0).costPrice()).as("judged against ITS batch's cost").isEqualByComparingTo("150");
        assertThat(lines.get(0).priceReason()).isEqualTo("Batch B-0912");
        assertThat(lines.get(0).catalogPrice()).as("the product price is still snapshotted").isEqualByComparingTo("250");
        assertThat(lines.get(1).quantity()).isEqualTo(3f);
        assertThat(lines.get(1).sellRate()).isEqualByComparingTo("250");
        assertThat(lines.get(1).totalAmount()).isEqualByComparingTo("750.00");

        List<StockReservationLine> held = reserved();
        assertThat(held).hasSize(2);
        assertThat(held.get(0).getStockEntryId()).isEqualTo(OLD);
        assertThat(held.get(0).getLineRef()).isEqualTo(0);
        assertThat(held.get(0).getQuantity()).isEqualByComparingTo("7");
        assertThat(held.get(1).getStockEntryId()).isEqualTo(NEW);
        assertThat(held.get(1).getLineRef()).isEqualTo(1);
    }

    @Test
    @DisplayName("a batch with no price of its own sells at the product's price")
    void unpricedBatchUsesTheProductPrice() {
        plan(pick(OLD, "B-OLD", "4", null, "150", 0));

        service.addSell(sale(line(4f, "250", "250")));

        List<SagaLine> lines = writtenLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).sellRate()).isEqualByComparingTo("250");
        assertThat(reserved().get(0).getStockEntryId()).isEqualTo(OLD);
    }

    @Test
    @DisplayName("⭐ the cashier's own price wins: no split, no re-price — but the planned batches are still pinned")
    void aTypedPriceIsNeverRepriced() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0), pick(NEW, "B-1003", "3", "250", "210", 0));

        service.addSell(sale(line(10f, "230", "250")));   // the till put 250, the cashier typed 230

        List<SagaLine> lines = writtenLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).sellRate()).isEqualByComparingTo("230");
        assertThat(lines.get(0).totalAmount()).isEqualByComparingTo("2300.00");
        assertThat(reserved()).extracting(StockReservationLine::getStockEntryId).containsExactly(OLD, NEW);
    }

    @Test
    @DisplayName("an amount discount is shared across the parts and still adds up to the paisa")
    void anAmountDiscountIsShared() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0), pick(NEW, "B-1003", "3", "250", "210", 0));
        SellDTO s = line(10f, "250", "250");
        StockDTO st = new StockDTO();
        st.setBsellDiscount(new BigDecimal("100"));
        st.setBsellDiscountType("0");
        s.setStock(st);

        service.addSell(sale(s));

        List<SagaLine> lines = writtenLines();
        assertThat(lines.get(0).discount().add(lines.get(1).discount())).isEqualByComparingTo("100");
        assertThat(lines.get(0).discount()).isEqualByComparingTo("65.12");   // 100 × 1400 / 2150
        assertThat(s.getStock().getBsellDiscount()).as("the cashier's own line is untouched").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("⭐ a stock change since the plan refuses the sale with 'stock changed' — never re-priced")
    void aPinnedBatchGoneRefuses() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0));
        when(inventoryClient.reserve(any(StockReservationRequest.class))).thenReturn(new StockReservationResponse(
                null, ReservationStatus.OUT_OF_STOCK, List.of(),
                "batch changed: product 123 batch B-0912 has 2, 7 needed"));

        assertThatThrownBy(() -> service.addSell(sale(line(7f, "250", "250"))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Stock changed while this sale was open")
                .hasMessageContaining("'abc-123' batch B-0912 has 2, 7 needed")
                .hasMessageContaining("Nothing was charged");
        verify(saleWriter, never()).writePending(any(), anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    @DisplayName("short stock refuses at the plan, before any guard or hold, in the usual words")
    void shortAtThePlan() {
        when(inventoryClient.plan(any(StockReservationRequest.class))).thenReturn(new StockReservationResponse(
                null, ReservationStatus.OUT_OF_STOCK, List.of(), "product 123: only 3 sellable, 5 requested"));

        assertThatThrownBy(() -> service.addSell(sale(line(5f, "250", "250"))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("'abc-123': only 3 sellable, 5 requested");
        verify(inventoryClient, never()).reserve(any(StockReservationRequest.class));
    }

    @Test
    @DisplayName("the cashier's chosen batch travels to the plan as a preference")
    void theChosenBatchIsSentToThePlan() {
        plan(pick(NEW, "B-1003", "2", "250", "210", 0));
        SellDTO s = line(2f, "250", "250");
        s.setStockEntryId(NEW);

        service.addSell(sale(s));

        ArgumentCaptor<StockReservationRequest> c = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(inventoryClient).plan(c.capture());
        assertThat(c.getValue().getLines().get(0).getStockEntryId()).isEqualTo(NEW);
    }

    @Test
    @DisplayName("⭐ Latest (any mode but Per batch) never asks for a plan — FEFO exactly as before")
    void otherModesAreUnchanged() {
        mode = "latest";

        service.addSell(sale(line(10f, "250", "250")));

        verify(inventoryClient, never()).plan(any(StockReservationRequest.class));
        List<StockReservationLine> held = reserved();
        assertThat(held).hasSize(1);
        assertThat(held.get(0).getStockEntryId()).isNull();
        assertThat(writtenLines().get(0).sellRate()).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("bonus: paid units priced first; the free goods ride on the last part's batches")
    void bonusRidesOnTheLastPart() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0), pick(NEW, "B-1003", "4", "250", "210", 0));
        SellDTO s = line(10f, "250", "250");
        s.setBonusQuantity(1f);

        service.addSell(sale(s));

        List<SagaLine> lines = writtenLines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).bonusQuantity()).isNull();
        assertThat(lines.get(1).bonusQuantity()).isEqualTo(1f);
        assertThat(lines.get(1).quantity()).isEqualTo(3f);
        assertThat(reserved().get(1).getQuantity()).as("3 paid + 1 free from B-1003").isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("the preview answers with the same parts the sale would write, and holds nothing")
    void previewMatchesTheSale() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0), pick(NEW, "B-1003", "3", "250", "210", 0));

        SagaSellService.PerBatch pb = service.previewBatchPricing(sale(line(10f, "250", "250")));

        assertThat(pb.lines()).extracting(SagaLine::sellRate).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("200"), new BigDecimal("250"));
        assertThat(pb.batchPriced()).containsExactly(true, true);
        verify(inventoryClient, never()).reserve(any(StockReservationRequest.class));
        verify(saleWriter, never()).writePending(any(), anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    @DisplayName("a contract price (B2B rule) wins over the batch price")
    void aContractPriceWins() {
        plan(pick(OLD, "B-0912", "7", "200", "150", 0), pick(NEW, "B-1003", "3", "250", "210", 0));
        CustomerHistoryDTO dto = sale(line(10f, "240", "240"));
        com.myplus.business_service.dto.CustomerDTO c = new com.myplus.business_service.dto.CustomerDTO();
        c.setCustomerId(5L);
        dto.setCustomer(c);
        com.myplus.commerce.contracts.dto.PriceQuote q = new com.myplus.commerce.contracts.dto.PriceQuote();
        com.myplus.commerce.contracts.dto.PriceQuoteLine ql = com.myplus.commerce.contracts.dto.PriceQuoteLine.of(ABC, BigDecimal.TEN);
        ql.setUnitPrice(new BigDecimal("240"));
        ql.setRuleId(77L);
        ql.setReason("Contract: Al-Karam");
        q.setLines(List.of(ql));
        when(catalogClient.quote(any())).thenReturn(q);

        service.addSell(dto);

        List<SagaLine> lines = writtenLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).sellRate()).isEqualByComparingTo("240");
        assertThat(lines.get(0).priceReason()).isEqualTo("Contract: Al-Karam");
    }

    @Test
    @DisplayName("⭐ the preview marks a typed price as NOT batch-priced, so the till keeps it the cashier's")
    void previewMarksATypedPriceAsTheCashiers() {
        plan(pick(OLD, "B-0912", "2", "200", "150", 0));

        SagaSellService.PerBatch pb = service.previewBatchPricing(sale(line(2f, "230", "200")));

        assertThat(pb.lines()).singleElement().satisfies(l -> assertThat(l.sellRate()).isEqualByComparingTo("230"));
        assertThat(pb.batchPriced()).containsExactly(false);
    }
}
