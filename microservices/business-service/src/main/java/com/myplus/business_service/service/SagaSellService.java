package com.myplus.business_service.service;

import com.myplus.business_service.dto.CustomerHistoryDTO;
import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.CustomerHistory;
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

    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final SagaSaleWriter saleWriter;
    private final RequestUtil requestUtil;
    private final TaxService taxService;

    /** @return the invoice number of the recorded sale. */
    public String addSell(CustomerHistoryDTO dto) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long orgId = user.getOrganizationId();
        String idempotencyKey = UUID.randomUUID().toString();

        // G3 (slice 35): the org's tax policy, resolved once per sale.
        var taxSetting = taxService.settingsFor(orgId);

        // 1 + 2 + 2b: translate each line to a catalog productId, price it from catalog, and apply tax.
        List<SagaLine> lines = new ArrayList<>();
        List<StockReservationLine> reservationLines = new ArrayList<>();
        for (SellDTO s : dto.getSales()) {
            // M4e (slice 101): productId-native — every caller (POS + pharmacy) submits productId now; the legacy
            // itemId→ItemCatalogMap translation has been retired.
            Long productId = s.getProductId();
            if (productId == null) throw new RuntimeException("Sale line has no productId — submit productId-native.");
            ProductRef product = catalogClient.getProduct(productId);
            BigDecimal catalogPrice = (product != null && product.getSellingPrice() != null)
                    ? product.getSellingPrice() : BigDecimal.ZERO;
            // The rate this line SOLD at = what the cashier entered (they may override the catalog price on the
            // sell screen); fall back to the catalog master when no rate was submitted. The catalog price is
            // snapshotted separately so reports show BOTH "catalog price" and "sold at".
            BigDecimal soldRate = (s.getSellRate() != null && s.getSellRate().compareTo(BigDecimal.ZERO) > 0)
                    ? s.getSellRate() : catalogPrice;
            BigDecimal productTaxRate = product != null ? product.getTaxRate() : null;
            // Taxable base is the line total (qty×rate after discount). EXCLUSIVE adds on top; INCLUSIVE backs it out.
            TaxResult tax = taxService.taxForLine(s.getTotalAmount(), productTaxRate, taxSetting);
            lines.add(new SagaLine(productId, s.getQuantity(), soldRate, null,
                    s.getTotalAmount(), s.getNetAmount(), s.getSrp(),
                    tax.rate(), tax.tax(), tax.gross(), catalogPrice));
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
