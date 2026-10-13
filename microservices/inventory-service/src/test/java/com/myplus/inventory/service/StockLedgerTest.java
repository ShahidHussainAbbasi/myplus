package com.myplus.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.myplus.commerce.contracts.dto.StockImportLine;
import com.myplus.commerce.contracts.dto.StockMovementType;
import com.myplus.commerce.contracts.dto.StockPurchaseAdjust;
import com.myplus.commerce.contracts.dto.StockReturnLine;
import com.myplus.inventory.dto.StockDTOs.StockAdjustmentDTO;
import com.myplus.inventory.entity.Reservation;
import com.myplus.inventory.entity.ReservationPick;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.inventory.entity.StockAdjustment;
import com.myplus.inventory.entity.StockMovement;
import com.myplus.inventory.repository.ReservationRepository;
import com.myplus.inventory.repository.StockEntryRepository;
import com.myplus.inventory.repository.StockLevelRepository;
import com.myplus.inventory.repository.StockMovementRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SSR-0a (doc slices/110-stock-ledger-ssr.md) — every change of sellable on-hand writes ONE ledger row, with its type,
 * signed quantity and source, in the same transaction; after any sequence SUM(ledger) equals stock_levels.current_stock.
 * Real MySQL: the 5 writers are exercised as the callers use them.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class StockLedgerTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
        r.add("spring.cloud.config.enabled", () -> "false");
        r.add("spring.cloud.discovery.enabled", () -> "false");
        r.add("eureka.client.enabled", () -> "false");
    }

    private static final Long ORG = 71L, USER = 7L, P = 9001L;

    @Autowired private StockService stockService;
    @Autowired private StockImportService importService;
    @Autowired private ReservationService reservationService;
    @Autowired private StockLevelRepository levels;
    @Autowired private StockEntryRepository entries;
    @Autowired private ReservationRepository reservations;
    @Autowired private StockMovementRepository ledger;

    @BeforeEach
    void clean() {
        ledger.deleteAll();
        reservations.deleteAll();
        entries.deleteAll();
        levels.deleteAll();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.myplus.common.security.AuthenticatedUser(USER, "t@t.com", List.of(), ORG), null, List.of()));
    }

    @AfterEach
    void signOut() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    private BigDecimal onHand() {
        return levels.findByProductScoped(P, ORG, USER).map(l -> l.getCurrentStock()).orElse(BigDecimal.ZERO);
    }

    private BigDecimal ledgerSum() {
        return ledger.findAll().stream().filter(m -> P.equals(m.getProductId()))
                .map(StockMovement::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<StockMovement> rows(String type) {
        return ledger.findAll().stream().filter(m -> type.equals(m.getMovementType())).toList();
    }

    private void purchase(float qty, String bill) {
        importService.importStock(List.of(StockImportLine.builder().productId(P).quantity(qty).batchNo("B-" + bill)
                .purchasePrice(new BigDecimal("50")).movementType(StockMovementType.PURCHASE).sourceRef(bill).build()), ORG, USER);
    }

    /** A sale as the saga leaves it before confirm: one pick of `qty` from the product's first batch, reserved. */
    private String reserved(BigDecimal qty) {
        var batch = entries.findAll().stream().filter(e -> P.equals(e.getProductId())).findFirst().orElseThrow();
        batch.setReservedQuantity(qty);
        entries.save(batch);
        Reservation r = Reservation.builder().reservationId(UUID.randomUUID().toString())
                .status(ReservationStatus.RESERVED).organizationId(ORG).userId(USER).build();
        r.getPicks().add(ReservationPick.builder().reservation(r).stockEntryId(batch.getId()).productId(P)
                .batchNo(batch.getBatchNo()).quantity(qty).unitCost(new BigDecimal("50")).build());
        return reservations.save(r).getReservationId();
    }

    @Test
    void a_purchase_writes_one_PURCHASE_row_with_its_bill() {
        purchase(10f, "PUR-1");
        List<StockMovement> r = rows(StockMovementType.PURCHASE);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getQuantity()).isEqualByComparingTo("10");
        assertThat(r.get(0).getSourceRef()).isEqualTo("PUR-1");
        assertThat(r.get(0).getOrganizationId()).isEqualTo(ORG);
        assertThat(r.get(0).getStockEntryId()).as("the batch it booked in").isNotNull();
        assertThat(r.get(0).getBusinessDate()).isNotNull();
    }

    @Test
    void an_import_from_an_older_caller_is_a_RECEIPT() {
        importService.importStock(List.of(StockImportLine.builder().productId(P).quantity(2f).build()), ORG, USER);
        assertThat(rows(StockMovementType.RECEIPT)).hasSize(1);
    }

    @Test
    void a_correction_is_an_ADJUSTMENT_of_the_applied_quantity() {
        purchase(10f, "PUR-2");
        stockService.recordAdjustment(StockAdjustmentDTO.builder().productId(P)
                .adjustmentType(StockAdjustment.AdjustmentType.DECREASE).build(),
                new BigDecimal("2"), "count", null, ORG, USER);
        List<StockMovement> r = rows(StockMovementType.ADJUSTMENT);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getQuantity()).isEqualByComparingTo("-2");
        assertThat(r.get(0).getSourceRef()).startsWith("ADJ-");
    }

    @Test
    void a_purchase_return_is_PURCHASE_RETURN_and_an_edit_without_a_type_is_PURCHASE_EDIT() {
        purchase(10f, "PUR-3");
        stockService.reconcilePurchase(StockPurchaseAdjust.builder().productId(P).batchNo("B-PUR-3").delta(-3f)
                .movementType(StockMovementType.PURCHASE_RETURN).sourceRef("PUR-3").build());
        stockService.reconcilePurchase(StockPurchaseAdjust.builder().productId(P).batchNo("B-PUR-3").delta(2f).build());
        assertThat(rows(StockMovementType.PURCHASE_RETURN)).singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).isEqualByComparingTo("-3"));
        assertThat(rows(StockMovementType.PURCHASE_EDIT)).singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).isEqualByComparingTo("2"));
    }

    @Test
    void a_confirmed_sale_is_a_SALE_out_with_the_reservation_as_its_source() {
        purchase(10f, "PUR-4");
        String resv = reserved(new BigDecimal("4"));
        reservationService.confirm(resv, ORG, USER);
        assertThat(rows(StockMovementType.SALE)).singleElement().satisfies(m -> {
            assertThat(m.getQuantity()).isEqualByComparingTo("-4");
            assertThat(m.getSourceRef()).isEqualTo(resv);
            assertThat(m.getUnitCost()).isEqualByComparingTo("50");
        });
    }

    @Test
    void a_quarantined_return_is_recorded_but_does_not_raise_sellable_stock() {
        purchase(10f, "PUR-5");
        String resv = reserved(new BigDecimal("4"));
        reservationService.confirm(resv, ORG, USER);
        reservationService.returnPicks(resv, List.of(new StockReturnLine(P, 1f)), true, ORG, USER, null, null);
        assertThat(rows(StockMovementType.SALE_RETURN)).singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).isEqualByComparingTo("1"));
        assertThat(rows(StockMovementType.QUARANTINE)).singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).isEqualByComparingTo("-1"));
        assertThat(onHand()).isEqualByComparingTo("6");
        assertThat(ledgerSum()).isEqualByComparingTo(onHand());
    }

    @Test
    void after_a_mixed_sequence_the_ledger_adds_up_to_on_hand() {
        purchase(10f, "PUR-6");                                                           // +10
        importService.importStock(List.of(StockImportLine.builder().productId(P).quantity(3f)
                .movementType(StockMovementType.SALE_VOID).build()), ORG, USER);           // +3
        stockService.recordAdjustment(StockAdjustmentDTO.builder().productId(P)
                .adjustmentType(StockAdjustment.AdjustmentType.DECREASE).build(),
                new BigDecimal("2"), "count", null, ORG, USER);                            // −2
        String resv = reserved(new BigDecimal("4"));
        reservationService.confirm(resv, ORG, USER);                                      // −4
        reservationService.returnPicks(resv, List.of(new StockReturnLine(P, 2f)), false, ORG, USER,
                StockMovementType.SALE_EDIT, "INV-9");                                     // +2

        assertThat(onHand()).isEqualByComparingTo("9");
        assertThat(ledgerSum()).as("SUM(ledger) = on-hand").isEqualByComparingTo(onHand());
        assertThat(rows(StockMovementType.SALE_EDIT)).singleElement()
                .satisfies(m -> assertThat(m.getSourceRef()).isEqualTo("INV-9"));
    }

    @Test
    void a_refused_change_leaves_no_ledger_row() {
        purchase(1f, "PUR-7");
        long before = ledger.count();
        assertThatThrownBy(() -> stockService.recordAdjustment(StockAdjustmentDTO.builder().productId(P)
                .adjustmentType(StockAdjustment.AdjustmentType.DECREASE).build(),
                new BigDecimal("5"), "count", null, ORG, USER)).isNotNull();
        assertThat(ledger.count()).as("rolled back with the refused correction").isEqualTo(before);
        assertThat(ledgerSum()).isEqualByComparingTo(onHand());
    }
}
