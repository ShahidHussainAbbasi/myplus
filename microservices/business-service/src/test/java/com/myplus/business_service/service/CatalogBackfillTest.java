package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.myplus.business_service.entity.Item;
import com.myplus.business_service.entity.ItemCatalogMap;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Stock;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.repository.ItemRepo;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.SellRepo;
import com.myplus.business_service.repository.StockRepo;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M3c.1 (slice 76) — the historical product_id backfill. Seeds a legacy Stock-linked sell + purchase (no product_id),
 * runs the backfill, and asserts product_id is stamped from the item→product map, nothing remains, and a re-run is a
 * no-op (idempotent). Tenant-scoped. Real MySQL; skips without Docker.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class CatalogBackfillTest {

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

    private static final Long ORG = 1L, USER = 1L, PRODUCT_ID = 500L;

    @Autowired private CatalogMigrationService service;
    @Autowired private ItemRepo itemRepo;
    @Autowired private ItemCatalogMapRepo mapRepo;
    @Autowired private StockRepo stockRepo;
    @Autowired private SellRepo sellRepo;
    @Autowired private PurchaseRepo purchaseRepo;
    @MockitoBean private CatalogClient catalogClient;     // not used by the backfill; mocked to keep the context light
    @MockitoBean private InventoryClient inventoryClient;

    @Test
    void backfills_product_id_onto_legacy_stock_linked_sells_and_purchases() {
        // a legacy item, mapped to a catalog product
        Item item = new Item();
        item.setIname("Legacy_" + System.nanoTime());
        item.setOrganizationId(ORG); item.setUserId(USER);
        item = itemRepo.save(item);
        mapRepo.save(ItemCatalogMap.builder().itemId(item.getId()).productId(PRODUCT_ID).organizationId(ORG).build());

        // a Stock row for that item, and a legacy sell + purchase linked to it with NO product_id
        Stock stock = new Stock();
        stock.setItemId(item.getId()); stock.setBatchNo("B1"); stock.setStock(10f);
        stock.setOrganizationId(ORG); stock.setUserId(USER);
        stock = stockRepo.save(stock);

        Sell sell = new Sell();
        sell.setStock(stock); sell.setProductId(null); sell.setQuantity(2f);
        sell.setOrganizationId(ORG); sell.setUserId(USER);
        sell = sellRepo.save(sell);

        Purchase purchase = new Purchase();
        purchase.setStock(stock); purchase.setProductId(null); purchase.setQuantity(5f);
        purchase.setOrganizationId(ORG); purchase.setUserId(USER);
        purchase = purchaseRepo.save(purchase);

        // backfill
        CatalogMigrationService.BackfillResult r = service.backfillProductIds(ORG, USER);
        assertThat(r.sellsBackfilled()).isEqualTo(1);
        assertThat(r.purchasesBackfilled()).isEqualTo(1);
        assertThat(r.sellsRemaining()).isEqualTo(0L);
        assertThat(r.purchasesRemaining()).isEqualTo(0L);

        // product_id is now stamped (re-read — the @Modifying query cleared the context)
        assertThat(sellRepo.findById(sell.getSellId()).orElseThrow().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(purchaseRepo.findById(purchase.getPurchaseId()).orElseThrow().getProductId()).isEqualTo(PRODUCT_ID);

        // idempotent: a second run backfills nothing
        CatalogMigrationService.BackfillResult again = service.backfillProductIds(ORG, USER);
        assertThat(again.sellsBackfilled()).isEqualTo(0);
        assertThat(again.purchasesBackfilled()).isEqualTo(0);
    }
}
