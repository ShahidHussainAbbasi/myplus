package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.List;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.Capability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * RST-R2a — the two refusals a typed sale can hit, and the far more important case that neither fires.
 *
 * <p>Design: {@code microservices/docs/slices/rst-r2a-order-types.md}.
 *
 * <h3>⚠ The case that carries this file is {@link #anUntypedSaleIsNeverRefused()}</h3>
 * Almost every sale in almost every tenant carries no order type at all. If the capability check or the
 * delivery check ever fires on those, this feature stops being a restaurant feature and becomes an outage
 * for every shop on the platform. Both refusals are therefore asserted to be SILENT on an untyped sale,
 * with the capability explicitly denied — the state a shop that never bought this is in.
 *
 * <p>The refusals are also asserted to happen BEFORE the allocator is called. A refusal after a reserve
 * leaks held stock until the lease lapses, and that is invisible from the error message.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderTypeSaleGuardTest {

    private static final long BURGER = 70L;

    @Mock private CatalogClient catalogClient;
    @Mock private InventoryClient inventoryClient;
    @Mock private SagaSaleWriter saleWriter;
    @Mock private RequestUtil requestUtil;
    @Mock private TaxService taxService;
    @Mock private SaleCosting saleCosting;
    @Mock private com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;
    @Mock private com.myplus.business_service.repository.PurchaseRepo purchaseRepo;
    @Mock private com.myplus.common.settings.SettingsService settingsService;
    @Mock private GlOutboxService glOutboxService;
    @Mock private AuditService auditService;
    @Mock private PeriodLockGuard periodLockGuard;
    @Mock private StoreCreditService storeCreditService;
    @Mock private com.myplus.business_service.repository.CustomerRepo customerRepo;
    @Mock private CreditStandingService creditStandingService;
    @Mock private SerialUnitService serialUnitService;
    @Mock private com.myplus.common.settings.CapabilityService capabilityService;
    @Mock private com.myplus.business_service.repository.SellRepo sellRepo;

    @InjectMocks private SagaSellService service;

    /*
     * ⚠ SagaSellService MIXES constructor injection with @Autowired FIELD injection, and @InjectMocks stops
     * after the constructor — so every field-injected dependency stays null and the first one reached NPEs
     * before the subject is touched. Documented at length in SagaSellServiceTest; repeated here rather than
     * cleverly avoided.
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
        Mockito.lenient().when(saleCosting.cogsFromPicks(anyList(), anyList())).thenReturn(new BigDecimal("6.00"));
        Mockito.lenient().when(saleCosting.cogs(anyList(), anyList())).thenReturn(new BigDecimal("6.00"));
        Mockito.lenient().when(catalogClient.getProduct(BURGER)).thenReturn(
                ProductRef.builder().id(BURGER).name("Zinger Burger")
                        .sellingPrice(new BigDecimal("350.00")).madeToOrder(true).build());
        Mockito.lenient().when(saleWriter.writePending(any(), anyString(), anyString(), any(), anyList(), anyList()))
                .thenReturn(invoice());
        Mockito.lenient().when(inventoryClient.reserve(any(StockReservationRequest.class)))
                .thenReturn(new StockReservationResponse("R1", ReservationStatus.RESERVED, List.of(), null));
    }

    private static CustomerHistory invoice() {
        CustomerHistory ch = new CustomerHistory();
        ch.setCustomer_history_id(910L);
        ch.setInvoiceNo("INV-000910");
        return ch;
    }

    /** A one-line sale of a made-to-order burger, with the given type and contact. */
    private static CustomerHistoryDTO sale(String orderType, String contact) {
        SellDTO line = new SellDTO();
        line.setProductId(BURGER);
        line.setQuantity(1f);
        line.setTotalAmount(new BigDecimal("350.00"));
        line.setNetAmount(new BigDecimal("350.00"));

        CustomerDTO customer = new CustomerDTO();
        customer.setName("Walk-in");
        customer.setContact(contact);

        CustomerHistoryDTO dto = new CustomerHistoryDTO();
        dto.setSales(List.of(line));
        dto.setCustomer(customer);
        dto.setOrderType(orderType);
        return dto;
    }

    @Test
    @DisplayName("⭐⭐ THE ONE THAT MATTERS: a sale with NO order type is never refused, capability off")
    void anUntypedSaleIsNeverRefused() {
        /*
         * This is the state of essentially the whole platform: no capability, no order type. If either
         * guard fired here, every till in every tenant would stop. The capability is made to THROW if it is
         * ever consulted, which is a stronger assertion than checking the sale succeeded — it proves the
         * guard did not merely tolerate the untyped sale, it never asked the question at all.
         */
        doThrow(new com.myplus.common.web.exception.ValidationException("capability must not be consulted"))
                .when(capabilityService).assertEnabled(Capability.ORDER_TYPES);

        assertThatCode(() -> service.addSell(sale(null, null)))
                .as("an ordinary shop's sale is untouched by this feature")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐⭐ a typed sale in a tenant without the capability is refused BEFORE the allocator")
    void aTypedSaleWithoutTheCapabilityIsRefused() {
        doThrow(new com.myplus.common.web.exception.ValidationException(
                "\"Dine-in, take-away and delivery\" is not switched on for your business."))
                .when(capabilityService).assertEnabled(Capability.ORDER_TYPES);

        assertThatThrownBy(() -> service.addSell(sale("DINE_IN", null)))
                .hasMessageContaining("not switched on");

        // A refusal after a reserve leaks held stock until the lease lapses, and nothing in the error says so.
        Mockito.verify(inventoryClient, Mockito.never()).reserve(any(StockReservationRequest.class));
        Mockito.verify(saleWriter, Mockito.never())
                .writePending(any(), anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    @DisplayName("⭐⭐ DELIVERY with no contact is refused — there is nowhere to send it")
    void deliveryWithoutAContactIsRefused() {
        assertThatThrownBy(() -> service.addSell(sale("DELIVERY", "   ")))
                .hasMessageContaining("delivery order needs a customer contact");

        Mockito.verify(inventoryClient, Mockito.never()).reserve(any(StockReservationRequest.class));
    }

    @Test
    @DisplayName("⭐ THE CONTROL: dine-in and take-away need no customer at all")
    void dineInAndTakeAwayNeedNoContact() {
        /*
         * Without this, the case above passes just as well if the guard demands a contact for EVERY typed
         * order — which would put a required field in front of a walk-in buying a burger, and would be
         * reported as "the till got slower" rather than as this change.
         */
        assertThatCode(() -> service.addSell(sale("DINE_IN", null))).doesNotThrowAnyException();
        assertThatCode(() -> service.addSell(sale("TAKE_AWAY", ""))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ a DELIVERY that HAS a contact sells")
    void deliveryWithAContactSells() {
        assertThat(service.addSell(sale("DELIVERY", "0335-2456847"))).isEqualTo("INV-000910");
    }

    @Test
    @DisplayName("an unreadable type costs the sale its type, never the sale")
    void anUnreadableTypeDoesNotLoseTheSale() {
        /*
         * The wire case. This value crosses a monolith proxy that re-serialises the payload, from a client
         * that may be older or newer than this server. "CURBSIDE" resolves to null, which means "not
         * recorded" — and because it is null, the capability is never consulted either, so an unknown
         * string cannot produce a licensing refusal for a shop that sent one by accident.
         */
        doThrow(new com.myplus.common.web.exception.ValidationException("capability must not be consulted"))
                .when(capabilityService).assertEnabled(Capability.ORDER_TYPES);

        assertThat(service.addSell(sale("CURBSIDE", null))).isEqualTo("INV-000910");
    }
}
