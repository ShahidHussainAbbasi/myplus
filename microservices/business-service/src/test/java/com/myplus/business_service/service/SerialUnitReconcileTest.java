package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.myplus.business_service.entity.SerialUnit;
import com.myplus.business_service.repository.SerialUnitRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.CapabilityService;
import com.myplus.common.web.exception.ValidationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SER-2/SER-3 (fix) — editing a bill's units, and putting a unit back.
 *
 * <p>Pure Mockito so it runs on every {@code mvn test}. These two operations are exactly the ones that were
 * MISSING rather than wrong: {@code updatePurchase} silently discarded the serials it was sent, and
 * {@code markReturned} had no caller at all, so a returned or voided handset stayed SOLD for ever and could
 * never be sold again. A test that only exercised the add path would have gone on passing through both.
 */
@ExtendWith(MockitoExtension.class)
class SerialUnitReconcileTest {

    private static final Long ORG = 44L;
    private static final Long PURCHASE = 916L;
    private static final Long PRODUCT = 2980L;

    @Mock private SerialUnitRepo serialUnitRepo;
    @Mock private CapabilityService capabilityService;
    @InjectMocks private SerialUnitService service;

    /** The legacy 4-arg constructor, as PurchaseStockInTest and SagaSellServiceTest already use. */
    private final AuthenticatedUser user =
            new AuthenticatedUser(137L, "owner.mobile@myplus.com", List.of(), ORG);

    private SerialUnit unit(String serial, String status, String invoiceNo) {
        return SerialUnit.builder()
                .serialUnitId((long) serial.hashCode())
                .organizationId(ORG).userId(137L)
                .productId(PRODUCT).serialNo(serial)
                .conditionGrade(SerialUnit.NEW).status(status)
                .purchaseId(PURCHASE).invoiceNo(invoiceNo)
                .dated(LocalDateTime.now()).updated(LocalDateTime.now())
                .build();
    }

    // ── reconcileForPurchase: what an EDIT does ────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ a corrected IMEI replaces the wrong one — the defect that was reported")
    void edit_replaces_a_mistyped_serial() {
        /*
         * The whole bug in one case. The operator typed 123ASD, saw it was wrong, opened the bill and saved
         * 123ASX. Before this fix `updatePurchase` took the parameter and dropped it: the screen reported
         * success and the register still held 123ASD.
         */
        when(serialUnitRepo.findByPurchase(ORG, PURCHASE))
                .thenReturn(new ArrayList<>(List.of(unit("123ASD", SerialUnit.IN_STOCK, null))));
        when(serialUnitRepo.findLive(eq(ORG), eq("123ASX"))).thenReturn(Optional.empty());

        int held = service.reconcileForPurchase(PURCHASE, PRODUCT, "123ASX", true, 1f, "NEW", user);

        assertThat(held).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SerialUnit>> removed = ArgumentCaptor.forClass(List.class);
        verify(serialUnitRepo).deleteAll(removed.capture());
        assertThat(removed.getValue()).extracting(SerialUnit::getSerialNo).containsExactly("123ASD");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SerialUnit>> added = ArgumentCaptor.forClass(List.class);
        verify(serialUnitRepo).saveAll(added.capture());
        assertThat(added.getValue()).extracting(SerialUnit::getSerialNo).containsExactly("123ASX");
    }

    @Test
    @DisplayName("a serial that did not change is left alone — never deleted and re-inserted")
    void edit_keeps_untouched_units() {
        /*
         * Re-inserting an unchanged unit would collide with V52's unique index on the live serial, and the
         * edit would fail on a bill nobody had actually changed. It would also lose the unit's original
         * received-on date, which is the one thing a warranty claim reads.
         */
        when(serialUnitRepo.findByPurchase(ORG, PURCHASE))
                .thenReturn(new ArrayList<>(List.of(unit("AAA", SerialUnit.IN_STOCK, null),
                                                    unit("BBB", SerialUnit.IN_STOCK, null))));

        service.reconcileForPurchase(PURCHASE, PRODUCT, "AAA, BBB", true, 2f, "NEW", user);

        verify(serialUnitRepo, never()).deleteAll(any());
        verify(serialUnitRepo, never()).saveAll(any());
        verify(serialUnitRepo, never()).findLive(anyLong(), anyString());   // nothing new was claimed
    }

    @Test
    @DisplayName("⭐ a unit that has been SOLD cannot be removed from the bill")
    void edit_refuses_to_un_receive_a_sold_unit() {
        /*
         * Deleting it would erase the only record tying a customer to the handset in their hand. The refusal
         * names the invoice, because the operator's next step is to take a sale return — and they cannot work
         * that out from "could not save".
         */
        when(serialUnitRepo.findByPurchase(ORG, PURCHASE))
                .thenReturn(new ArrayList<>(List.of(unit("SOLDONE", SerialUnit.SOLD, "INV-000084"))));

        assertThatThrownBy(() ->
                service.reconcileForPurchase(PURCHASE, PRODUCT, "OTHER", true, 1f, "NEW", user))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("SOLDONE")
                .hasMessageContaining("INV-000084");

        verify(serialUnitRepo, never()).deleteAll(any());
        verify(serialUnitRepo, never()).saveAll(any());   // ⭐ a refusal changed NOTHING
    }

    @Test
    @DisplayName("the serial count must still match the quantity after an edit")
    void edit_enforces_count_against_quantity() {
        when(serialUnitRepo.findByPurchase(ORG, PURCHASE)).thenReturn(new ArrayList<>());

        assertThatThrownBy(() ->
                service.reconcileForPurchase(PURCHASE, PRODUCT, "AAA, BBB", true, 3f, "NEW", user))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("3 unit(s)")
                .hasMessageContaining("2 serial");
    }

    @Test
    @DisplayName("a serial live on ANOTHER bill is refused, with the reason")
    void edit_refuses_a_serial_already_in_stock_elsewhere() {
        when(serialUnitRepo.findByPurchase(ORG, PURCHASE)).thenReturn(new ArrayList<>());
        when(serialUnitRepo.findLive(ORG, "DUP"))
                .thenReturn(Optional.of(unit("DUP", SerialUnit.IN_STOCK, null)));

        assertThatThrownBy(() ->
                service.reconcileForPurchase(PURCHASE, PRODUCT, "DUP", true, 1f, "NEW", user))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already in stock");
    }

    @Test
    @DisplayName("an ordinary product on an ordinary edit does no work at all")
    void edit_is_inert_for_untracked_products() {
        when(serialUnitRepo.findByPurchase(ORG, PURCHASE)).thenReturn(new ArrayList<>());

        assertThat(service.reconcileForPurchase(PURCHASE, PRODUCT, null, false, 5f, null, user)).isZero();

        // Not even the capability is consulted: a shop that does not track serials must not pay for the
        // feature on every purchase edit it makes.
        verify(capabilityService, never()).assertEnabled(any());
    }

    // ── restoreForReturn / restoreForVoid: putting a unit BACK ─────────────────────────────────────

    @Test
    @DisplayName("⭐ a whole-line return puts the handset back on the shelf")
    void return_restocks_the_whole_line() {
        when(serialUnitRepo.findBySaleInvoice(ORG, "INV-000084"))
                .thenReturn(List.of(unit("AAA", SerialUnit.SOLD, "INV-000084")));
        when(serialUnitRepo.markReturned(eq(ORG), eq("AAA"), any())).thenReturn(1);

        assertThat(service.restoreForReturn(ORG, "INV-000084", PRODUCT, null, 1f))
                .containsExactly("AAA");
    }

    @Test
    @DisplayName("⭐ a PARTIAL return of a tracked line is refused until the serial is named")
    void return_refuses_to_guess_which_unit_came_back() {
        /*
         * Two handsets left on this invoice and one is coming back. Restocking whichever the query happened
         * to return first would put the wrong IMEI on sale, and nobody would find out until the customer
         * still holding it made a warranty claim.
         */
        when(serialUnitRepo.findBySaleInvoice(ORG, "INV-000084"))
                .thenReturn(List.of(unit("AAA", SerialUnit.SOLD, "INV-000084"),
                                    unit("BBB", SerialUnit.SOLD, "INV-000084")));

        assertThatThrownBy(() -> service.restoreForReturn(ORG, "INV-000084", PRODUCT, null, 1f))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("2 serial-tracked unit(s)");

        verify(serialUnitRepo, never()).markReturned(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("a named serial that never left on this invoice is refused")
    void return_refuses_a_serial_from_another_invoice() {
        when(serialUnitRepo.findBySaleInvoice(ORG, "INV-000084"))
                .thenReturn(List.of(unit("AAA", SerialUnit.SOLD, "INV-000084")));

        assertThatThrownBy(() -> service.restoreForReturn(ORG, "INV-000084", PRODUCT, "ZZZ", 1f))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("was not sold on invoice INV-000084");
    }

    @Test
    @DisplayName("a sale that put no units in the register is untouched by a return")
    void return_is_inert_for_untracked_lines() {
        when(serialUnitRepo.findBySaleInvoice(ORG, "INV-000090")).thenReturn(List.of());

        assertThat(service.restoreForReturn(ORG, "INV-000090", PRODUCT, null, 3f)).isEmpty();
        verify(serialUnitRepo, never()).markReturned(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("⭐ a VOID puts every unit on the invoice back, across products")
    void void_restocks_everything_on_the_invoice() {
        /*
         * No serials to ask for: a void reverses the WHOLE document. The product filter is deliberately not
         * applied — a voided invoice for a handset AND its charger must release both.
         */
        SerialUnit other = unit("BBB", SerialUnit.SOLD, "INV-000084");
        other.setProductId(3981L);
        when(serialUnitRepo.findBySaleInvoice(ORG, "INV-000084"))
                .thenReturn(List.of(unit("AAA", SerialUnit.SOLD, "INV-000084"), other));
        when(serialUnitRepo.markReturned(anyLong(), anyString(), any())).thenReturn(1);

        assertThat(service.restoreForVoid(ORG, "INV-000084")).containsExactly("AAA", "BBB");
    }

    @Test
    @DisplayName("a void never throws — the invoice is already reversed by the time this runs")
    void void_survives_a_stubborn_unit() {
        lenient().when(serialUnitRepo.findBySaleInvoice(ORG, "INV-000084"))
                .thenReturn(List.of(unit("AAA", SerialUnit.SOLD, "INV-000084")));
        when(serialUnitRepo.markReturned(anyLong(), anyString(), any()))
                .thenThrow(new RuntimeException("database said no"));

        assertThat(service.restoreForVoid(ORG, "INV-000084")).isEmpty();   // reported, not thrown
    }

    // ── join(): the other half of the round trip ───────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ join() produces exactly what split() reads back")
    void join_round_trips_through_split() {
        /*
         * The grid renders join(); the edit form posts that same string; the server splits it. If the two
         * ever disagreed, opening a bill and saving it unchanged would rewrite its register — which is the
         * silent-data-loss shape this whole fix exists to close.
         */
        List<SerialUnit> units = List.of(unit("AAA", SerialUnit.IN_STOCK, null),
                                         unit("BBB", SerialUnit.IN_STOCK, null));
        String joined = SerialUnitService.join(units);

        assertThat(joined).isEqualTo("AAA, BBB");
        assertThat(SerialUnitService.split(joined)).containsExactly("AAA", "BBB");
    }

    @Test
    @DisplayName("a bill with no units joins to null, not to an empty string")
    void join_of_nothing_is_null() {
        // "" would render an empty cell that editRecord copies into the form as an empty box — which now
        // MEANS "remove them all". Null keeps the absent case absent.
        assertThat(SerialUnitService.join(List.of())).isNull();
        assertThat(SerialUnitService.join(null)).isNull();
    }
}
