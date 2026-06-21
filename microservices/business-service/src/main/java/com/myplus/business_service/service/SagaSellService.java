package com.myplus.business_service.service;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.repository.ItemCatalogMapRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.*;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sell↔stock saga orchestrator (slice 33, U3b), active when {@code trade.saga.enabled}. For a sale:
 * translate itemId→productId, price from catalog (D1), {@code reserve} inventory, write the PENDING sale
 * (committed), then {@code confirm}. Compensation: a write failure releases the hold; a confirm failure
 * leaves the invoice PENDING for the recovery relay (U3c) to re-drive (confirm is idempotent). Reserve is
 * idempotent on the per-sale key.
 */
@Service
@RequiredArgsConstructor
public class SagaSellService {

    private static final Logger LOG = LoggerFactory.getLogger(SagaSellService.class);

    private final ItemCatalogMapRepo itemCatalogMapRepo;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final SagaSaleWriter saleWriter;
    private final RequestUtil requestUtil;

    /** @return the invoice number of the recorded sale. */
    public String addSell(CustomerHistoryDTO dto) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long orgId = user.getOrganizationId();
        String idempotencyKey = UUID.randomUUID().toString();

        // 1 + 2: translate each line to a catalog productId and price it from catalog.
        List<SagaLine> lines = new ArrayList<>();
        List<StockReservationLine> reservationLines = new ArrayList<>();
        for (SellDTO s : dto.getSales()) {
            Long productId = itemCatalogMapRepo.findProductIdByItemId(s.getItemId(), orgId)
                    .orElseThrow(() -> new RuntimeException(
                            "Item " + s.getItemId() + " is not migrated to catalog; run the catalog migration first"));
            ProductRef product = catalogClient.getProduct(productId);
            BigDecimal sellRate = (product != null && product.getSellingPrice() != null)
                    ? product.getSellingPrice() : BigDecimal.ZERO;
            lines.add(new SagaLine(productId, s.getQuantity(), sellRate, null,
                    s.getTotalAmount(), s.getNetAmount(), s.getSrp()));
            reservationLines.add(new StockReservationLine(productId, BigDecimal.valueOf(s.getQuantity())));
        }

        // 3: reserve (FEFO). OUT_OF_STOCK -> reject the sale (nothing held, nothing written).
        StockReservationResponse reservation =
                inventoryClient.reserve(new StockReservationRequest(idempotencyKey, reservationLines));
        if (reservation == null || reservation.getStatus() != ReservationStatus.RESERVED) {
            String why = (reservation != null && reservation.getMessage() != null) ? ": " + reservation.getMessage() : "";
            throw new RuntimeException("Insufficient stock to complete the sale" + why);
        }
        String reservationId = reservation.getReservationId();

        // 4: write the PENDING sale (its own committed tx). On failure, release the hold and abort.
        CustomerHistory ch;
        try {
            ch = saleWriter.writePending(dto, reservationId, idempotencyKey, user, lines);
        } catch (RuntimeException writeFailure) {
            safeRelease(reservationId);
            throw writeFailure;
        }

        // 5 + 6: confirm -> mark CONFIRMED. A confirm failure leaves the invoice PENDING for the relay (U3c);
        // the sale is recorded and the held stock stays held until confirmed.
        try {
            inventoryClient.confirm(reservationId);
            saleWriter.markStatus(ch.getCustomer_history_id(), "CONFIRMED");
        } catch (RuntimeException confirmFailure) {
            LOG.warn("Saga confirm failed for reservation {} (invoice {}); left PENDING for the recovery relay",
                    reservationId, ch.getInvoiceNo(), confirmFailure);
        }
        return ch.getInvoiceNo();
    }

    private void safeRelease(String reservationId) {
        try {
            inventoryClient.release(reservationId);
        } catch (RuntimeException ignore) {
            LOG.warn("Compensating release failed for reservation {} (held stock will lapse/cleanup later)", reservationId);
        }
    }
}
