package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.ReservationStatus;
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
 * RST — a MADE-TO-ORDER item reserves no stock, because the shop holds none of it.
 *
 * <h3>The defect this closes, and how it was found</h3>
 * {@code SagaSellService} built a {@code StockReservationLine} for EVERY sale line with no exemption, so the
 * platform could not sell anything it did not physically hold. The restaurant R1 gate ran expecting to pass
 * and answered <em>"Not enough sellable stock — 'Zinger Burger': only 0 sellable, 2 requested"</em>. The
 * product was right and the design was wrong: a restaurant holds buns, fillets and oil, and assembles a
 * burger when the order lands. A salon cannot stock a haircut either.
 *
 * <h3>⚠ The case that keeps this honest is {@link #aStockedItemStillReserves()}</h3>
 * {@code pos.sale.negativeStockAllowed} was deliberately REMOVED from the settings catalogue, with a warning
 * not to re-add one without building the cross-service oversell path. The danger in this change is that it
 * quietly becomes that switch. So the mixed case asserts the exemption applies to the flagged product
 * <b>and only</b> to it — a restaurant's cold drinks are stocked and must still be refused when they run out.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MadeToOrderReservationTest {

    private static final long BURGER = 50L;    // made to order — no finished stock
    private static final long COLA   = 51L;    // bought in, stocked, reserves normally

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

    private static ProductRef product(long id, String name, boolean madeToOrder) {
        return ProductRef.builder().id(id).name(name).sellingPrice(new BigDecimal("10.00"))
                .madeToOrder(madeToOrder).build();
    }

    private static SellDTO line(long productId, float qty) {
        SellDTO s = new SellDTO();
        s.setProductId(productId);
        s.setQuantity(qty);
        s.setTotalAmount(new BigDecimal("10.00"));
        s.setNetAmount(new BigDecimal("10.00"));
        return s;
    }

    private static CustomerHistoryDTO sale(SellDTO... lines) {
        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setSales(List.of(lines));
        return dto;
    }

    private static CustomerHistory invoice() {
        CustomerHistory ch = new CustomerHistory();
        ch.setCustomer_history_id(900L);
        ch.setInvoiceNo("INV-000900");
        return ch;
    }

    /*
     * ⚠ SagaSellService MIXES constructor injection with @Autowired FIELD injection, and @InjectMocks stops
     * after the constructor — so every field-injected dependency stays null and the first one reached NPEs
     * before any test touches its subject. SagaSellServiceTest documents this at length; the same wiring is
     * required here rather than cleverly avoided.
     */
    @BeforeEach
    void injectFieldWiredDependencies() {
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
    }

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(requestUtil.getCurrentUser())
                .thenReturn(new AuthenticatedUser(1L, "cashier@test.com", List.of(), 1L));
        Mockito.lenient().when(taxService.settingsFor(anyLong()))
                .thenReturn(TaxSetting.builder().enabled(false).build());
        Mockito.lenient().when(taxService.taxForLine(any(), any(), any()))
                .thenReturn(new TaxResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        // The GL post is best-effort and its NPE would be swallowed — an unstubbed cost would let a test pass
        // having proved nothing about the cost basis.
        Mockito.lenient().when(saleCosting.cogsFromPicks(anyList(), anyList())).thenReturn(new BigDecimal("6.00"));
        Mockito.lenient().when(saleCosting.cogs(anyList(), anyList())).thenReturn(new BigDecimal("6.00"));
        Mockito.lenient().when(saleWriter.writePending(any(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenReturn(invoice());
        Mockito.lenient().when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
    }

    @Test
    @DisplayName("⭐⭐ THE DEFECT: a burger with no stock sells, because a kitchen holds no burgers")
    void aMadeToOrderItemSellsWithNoStock() {
        when(catalogClient.getProduct(BURGER)).thenReturn(product(BURGER, "Zinger Burger", true));

        String invoiceNo = service.addSell(sale(line(BURGER, 2f)));

        assertThat(invoiceNo).isEqualTo("INV-000900");
        // Nothing to reserve, so the allocator is never asked. Before this change the sale was refused with
        // "only 0 sellable, 2 requested" — the exact message the R1 gate answered with.
        verify(inventoryClient, never()).reserve(any(StockReservationRequest.class));
    }

    @Test
    @DisplayName("⭐⭐ THE CONTROL: a STOCKED item on the same sale still reserves")
    void aStockedItemStillReserves() {
        /*
         * The case that stops this becoming a negative-stock switch. A restaurant really does stock cold
         * drinks, and those must keep checking their stock — "only 0 sellable" on a cola is the system
         * working. If a later change made the exemption tenant-wide, the captured request below would come
         * back empty and this fails.
         */
        when(catalogClient.getProduct(BURGER)).thenReturn(product(BURGER, "Zinger Burger", true));
        when(catalogClient.getProduct(COLA)).thenReturn(product(COLA, "Cola 1.5L", false));

        service.addSell(sale(line(BURGER, 2f), line(COLA, 3f)));

        ArgumentCaptor<StockReservationRequest> req = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(inventoryClient).reserve(req.capture());
        List<StockReservationLine> held = req.getValue().getLines();

        assertThat(held).as("exactly one line reserved — the drink, never the burger").hasSize(1);
        assertThat(held.get(0).getItemId()).isEqualTo(COLA);
        assertThat(held.get(0).getQuantity()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("⭐ an ordinary shop is completely unaffected — the flag is false everywhere")
    void anOrdinaryProductIsUnchanged() {
        // The safety property of the whole change: every existing row is made_to_order = FALSE, so every
        // sale in every tenant reserves exactly as it did before.
        when(catalogClient.getProduct(COLA)).thenReturn(product(COLA, "Cola 1.5L", false));

        service.addSell(sale(line(COLA, 4f)));

        ArgumentCaptor<StockReservationRequest> req = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(inventoryClient).reserve(req.capture());
        assertThat(req.getValue().getLines()).hasSize(1);
        assertThat(req.getValue().getLines().get(0).getQuantity()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("⭐ a null flag reads as FALSE — an older ref must never accidentally skip the check")
    void aNullFlagReserves() {
        /*
         * ProductRef is a wire type. A ref built by an older service, or deserialised from a cached payload
         * written before this field existed, carries null here. Null must mean "reserves", because the
         * alternative is a sale silently skipping the stock check on goods the shop really holds.
         */
        when(catalogClient.getProduct(COLA)).thenReturn(
                ProductRef.builder().id(COLA).name("Cola 1.5L").sellingPrice(new BigDecimal("10.00")).build());

        service.addSell(sale(line(COLA, 1f)));

        verify(inventoryClient).reserve(any(StockReservationRequest.class));
    }
}
