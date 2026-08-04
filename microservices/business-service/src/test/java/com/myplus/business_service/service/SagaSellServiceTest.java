package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.common.security.AuthenticatedUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Slice 33, U3b — pure Mockito (always runs). Verifies the saga orchestration: happy path confirms;
 * OUT_OF_STOCK rejects before writing; a confirm failure leaves the invoice PENDING (no release, not marked).
 */
@ExtendWith(MockitoExtension.class)
class SagaSellServiceTest {

    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private SagaSaleWriter saleWriter;
    @Mock private RequestUtil requestUtil;
    @Mock private TaxService taxService;
    // Constructor deps too: @InjectMocks passes null for any constructor argument with no matching @Mock,
    // which is why customerHistoryRepo NPE'd. customerHistoryRepo arrived with SF-3 (idempotency dedup) and
    // purchaseRepo with SF-10 (line cost); neither slice updated this test, and -DskipTests hid both.
    @Mock private com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;
    @Mock private com.myplus.business_service.repository.PurchaseRepo purchaseRepo;
    // ── field-injected (@Autowired) dependencies ────────────────────────────────────────────────────
    // ALL SIX are listed deliberately. SagaSellService mixes constructor injection with @Autowired fields,
    // and Mockito populates only the constructor — so every field below stays null unless set explicitly in
    // @BeforeEach. Fixing these one NPE at a time costs a build per dependency; the whole set is wired at
    // once so adding a seventh fails loudly here rather than three slices later.
    @Mock private StoreCreditService storeCreditService;
    @Mock private GlOutboxService glOutboxService;
    @Mock private AuditService auditService;
    @Mock private com.myplus.common.settings.SettingsService settingsService;
    @Mock private com.myplus.business_service.repository.CustomerRepo customerRepo;
    /**
     * Added 2026-08-03, MISSING since commit c6d411f9 ("Finance period close") added the guard to addSell.
     * It went unnoticed because every build ran -DskipTests.
     *
     * <p><b>Declaring the @Mock is not enough here.</b> {@code SagaSellService} MIXES constructor injection
     * (private final + @RequiredArgsConstructor) with @Autowired FIELD injection, and Mockito's @InjectMocks
     * picks the biggest constructor — once that succeeds it does not also populate fields. So every
     * @Autowired field (periodLockGuard, glOutboxService, auditService, storeCreditService…) stays null and
     * no amount of @Mock fixes it. They have to be set explicitly, below.
     */
    @Mock private PeriodLockGuard periodLockGuard;
    @InjectMocks private SagaSellService service;

    @BeforeEach
    void injectFieldWiredDependencies() {
        // The guard runs first in addSell, so without this every test NPEs before reaching its own subject.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "periodLockGuard", periodLockGuard);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "storeCreditService", storeCreditService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "glOutboxService", glOutboxService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "auditService", auditService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "settingsService", settingsService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "customerRepo", customerRepo);
    }

    private CustomerHistoryDTO dtoWithOneLine() {
        SellDTO s = new SellDTO();
        s.setProductId(50L);   // M4e.d: productId-native (Item/ItemCatalogMap bridge retired)
        s.setQuantity(2f);
        s.setTotalAmount(new BigDecimal("20.00"));
        s.setNetAmount(new BigDecimal("20.00"));
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setSales(List.of(s));
        return dto;
    }

    private CustomerHistory invoice(Long id, String no) {
        CustomerHistory ch = new CustomerHistory();
        ch.setCustomer_history_id(id);
        ch.setInvoiceNo(no);
        return ch;
    }

    /**
     * Shared setup. {@code lenient()} because the closed-period test below rejects BEFORE any of this is
     * reached — which is the behaviour it exists to prove. Strict stubs would fail it for doing its job.
     */
    @BeforeEach
    void user() {
        org.mockito.Mockito.lenient().when(requestUtil.getCurrentUser())
                .thenReturn(new AuthenticatedUser(1L, "cashier@test.com", List.of(), 1L));
        // G3: tax disabled in these saga-orchestration tests — no tax applied, lines carry zero tax.
        org.mockito.Mockito.lenient().when(taxService.settingsFor(anyLong()))
                .thenReturn(TaxSetting.builder().enabled(false).build());
        org.mockito.Mockito.lenient().when(taxService.taxForLine(any(), any(), any()))
                .thenReturn(new TaxResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void happy_path_reserves_writes_confirms_and_marks_confirmed() {
        when(catalogClient.getProduct(50L))
                .thenReturn(new ProductRef(50L, "SKU", "Name", "ea", new BigDecimal("10.00"), null));
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
        when(saleWriter.writePending(any(), eq("R1"), anyString(), any(), anyList(), anyList()))
                .thenReturn(invoice(1000L, "INV-000001"));

        String invoiceNo = service.addSell(dtoWithOneLine());

        assertThat(invoiceNo).isEqualTo("INV-000001");
        verify(inventoryClient).confirm("R1");
        verify(saleWriter).markStatus(1000L, "CONFIRMED");
    }

    @Test
    void out_of_stock_rejects_before_writing_anything() {
        when(catalogClient.getProduct(50L))
                .thenReturn(new ProductRef(50L, "SKU", "Name", "ea", new BigDecimal("10.00"), null));
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse(null, ReservationStatus.OUT_OF_STOCK, List.of(), "no stock"));

        assertThatThrownBy(() -> service.addSell(dtoWithOneLine()))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Not enough sellable stock")
                .hasMessageContaining("no stock");

        verify(saleWriter, never()).writePending(any(), any(), any(), any(), any(), any());
    }

    /**
     * The period-close guard had NO unit test — which is how the missing mock above rotted unnoticed. A
     * closed period must stop the sale at the door: no reservation taken, nothing written. Rejecting only
     * AFTER reserving stock would leave a hold against a period the books are shut on.
     */
    @Test
    void a_sale_dated_in_a_CLOSED_period_is_rejected_before_anything_is_reserved() {
        doThrow(new PeriodClosedException("This period is closed (locked through 2026-07-31)."))
                .when(periodLockGuard).assertOpen(any(java.time.LocalDate.class));

        assertThatThrownBy(() -> service.addSell(dtoWithOneLine()))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("closed");

        verify(inventoryClient, never()).reserve(any(StockReservationRequest.class));
        verify(saleWriter, never()).writePending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirm_failure_leaves_invoice_pending_for_the_relay() {
        when(catalogClient.getProduct(50L))
                .thenReturn(new ProductRef(50L, "SKU", "Name", "ea", new BigDecimal("10.00"), null));
        when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
        when(saleWriter.writePending(any(), eq("R1"), anyString(), any(), anyList(), anyList()))
                .thenReturn(invoice(1000L, "INV-000002"));
        when(inventoryClient.confirm("R1")).thenThrow(new RuntimeException("inventory down"));

        String invoiceNo = service.addSell(dtoWithOneLine());

        assertThat(invoiceNo).isEqualTo("INV-000002");          // sale still recorded
        verify(saleWriter, never()).markStatus(1000L, "CONFIRMED");
        verify(inventoryClient, never()).release(anyString());  // PENDING left for the relay, hold retained
    }
}
