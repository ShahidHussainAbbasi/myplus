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
    private final com.myplus.business_service.repository.CustomerHistoryRepo customerHistoryRepo;   // SF-3 dedup
    private final com.myplus.business_service.repository.PurchaseRepo purchaseRepo;                 // SF-10 line cost

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.myplus.commerce.contracts.client.FinanceClient financeClient;   // F3b: post the SALE journal to the GL

    /** @return the invoice number of the recorded sale. */
    public String addSell(CustomerHistoryDTO dto) {
        AuthenticatedUser user = requestUtil.getCurrentUser();

        // SF-3: idempotent submission — one key per checkout attempt (client-supplied; fall back to a generated one
        // for legacy callers). If an invoice already exists for (org, key), this is a double-click / retry: return
        // the SAME invoice, with no second reserve and no second write.
        String idempotencyKey = (dto.getIdempotencyKey() != null && !dto.getIdempotencyKey().isBlank())
                ? dto.getIdempotencyKey() : UUID.randomUUID().toString();
        java.util.Optional<CustomerHistory> already =
                customerHistoryRepo.findFirstByOrganizationIdAndIdempotencyKey(user.getOrganizationId(), idempotencyKey);
        if (already.isPresent()) {
            LOG.info("addSell idempotent replay for key {} -> existing invoice {}", idempotencyKey, already.get().getInvoiceNo());
            return already.get().getInvoiceNo();
        }

        // SF-1/SF-2: the ONE authoritative line build (catalog price + sold rate + discount + tax + catalog
        // snapshot), shared with updateSell so add and edit produce identical lines.
        java.util.Map<Long, String> productNames = new java.util.HashMap<>();   // for a friendly out-of-stock message
        List<SagaLine> lines = buildLines(dto, productNames);
        List<StockReservationLine> reservationLines = new ArrayList<>();
        for (SagaLine l : lines) {
            reservationLines.add(new StockReservationLine(l.productId(), BigDecimal.valueOf(l.quantity())));
        }

        // 3: reserve (FEFO). OUT_OF_STOCK -> reject the sale (nothing held, nothing written).
        StockReservationResponse reservation =
                inventoryClient.reserve(new StockReservationRequest(idempotencyKey, reservationLines));
        if (reservation == null || reservation.getStatus() != ReservationStatus.RESERVED) {
            String reason = (reservation != null) ? reservation.getMessage() : null;
            throw new InsufficientStockException(friendlyOutOfStock(reason, productNames));
        }
        String reservationId = reservation.getReservationId();

        // 4: write the PENDING sale (its own committed tx). On failure, release the hold and abort.
        CustomerHistory ch;
        try {
            ch = saleWriter.writePending(dto, reservationId, idempotencyKey, user, lines);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // SF-3 race: a concurrent retry inserted this invoice first (unique idempotency index). The reservation
            // is idempotent per key (a shared hold owned by the winner) — do NOT release it; just return their invoice.
            LOG.info("addSell idempotent race for key {} -> returning the winner's invoice", idempotencyKey);
            return customerHistoryRepo.findFirstByOrganizationIdAndIdempotencyKey(user.getOrganizationId(), idempotencyKey)
                    .map(CustomerHistory::getInvoiceNo).orElseThrow(() -> dup);
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

        // F3b: auto-post the sale to the General Ledger (Dr Cash/AR, Cr Sales+Tax; + COGS from the line cost).
        // Best-effort — a GL hiccup must never fail the sale (reconcile later). Only on a NEW sale (not edits).
        try {
            if (financeClient != null) {
                BigDecimal cost = BigDecimal.ZERO;
                for (SagaLine l : lines)
                    if (l.costPrice() != null) cost = cost.add(l.costPrice().multiply(BigDecimal.valueOf(l.quantity())));
                financeClient.postEvent(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                        .eventType("SALE").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
                        .grandTotal(ch.getGrandTotal()).subTotal(ch.getSubTotal()).taxTotal(ch.getTaxTotal())
                        .cost(cost).paidAmount(ch.getPaidAmount()).method(ch.getPaymentMode()).build());
            }
        } catch (Exception ex) {
            LOG.warn("GL post failed for sale {} (sale recorded; reconcile later)", ch.getInvoiceNo(), ex);
        }
        return ch.getInvoiceNo();
    }

    /**
     * SF-1/SF-2: THE authoritative per-line build — used by addSell AND updateSell so a new sale and an edit
     * produce identical lines. Each line is priced from catalog (soldRate = cashier's rate or catalog fallback),
     * the DISCOUNT (amount/%) is resolved and the tax is applied on the DISCOUNTED base (qty×rate − discount), and
     * the catalog price is snapshotted. {@code productNames} (nullable) is filled productId→name for a friendly
     * out-of-stock message on the reserve step.
     */
    public List<SagaLine> buildLines(CustomerHistoryDTO dto, java.util.Map<Long, String> productNames) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long orgId = user.getOrganizationId();
        Long userId = user.getUserId();
        var taxSetting = taxService.settingsFor(orgId);   // G3: the org's tax policy, once per sale
        List<SagaLine> lines = new ArrayList<>();
        for (SellDTO s : dto.getSales()) {
            // M4e (slice 101): productId-native — every caller (POS + pharmacy) submits productId now.
            Long productId = s.getProductId();
            if (productId == null) throw new RuntimeException("Sale line has no productId — submit productId-native.");
            ProductRef product = catalogClient.getProduct(productId);
            String pName = (product != null && product.getName() != null) ? product.getName()
                    : (s.getItemName() != null ? s.getItemName() : ("product " + productId));
            if (productNames != null) productNames.put(productId, pName);
            BigDecimal catalogPrice = (product != null && product.getSellingPrice() != null)
                    ? product.getSellingPrice() : BigDecimal.ZERO;
            // The rate this line SOLD at = the cashier's rate (may override catalog); fall back to catalog. The
            // catalog price is snapshotted separately so reports show BOTH "catalog price" and "sold at".
            BigDecimal soldRate = (s.getSellRate() != null && s.getSellRate().compareTo(BigDecimal.ZERO) > 0)
                    ? s.getSellRate() : catalogPrice;
            BigDecimal productTaxRate = product != null ? product.getTaxRate() : null;
            // The line DISCOUNT reduces the bill; tax the DISCOUNTED base (the UI's discounted net is never submitted).
            BigDecimal lineTotal = s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal discount = resolveDiscount(s, lineTotal);
            String discountType = resolveDiscountType(s, discount);
            BigDecimal base = lineTotal.subtract(discount);
            if (base.compareTo(BigDecimal.ZERO) < 0) base = BigDecimal.ZERO;
            TaxResult tax = taxService.taxForLine(base, productTaxRate, taxSetting);
            // SF-10: snapshot the product's latest purchase rate as the unit COGS for margin reporting (null if
            // this product was never purchased in the tenant → margin shows blank for that line).
            BigDecimal costPrice = null;
            List<BigDecimal> recentCosts = purchaseRepo.findRecentCosts(productId, orgId, userId,
                    org.springframework.data.domain.PageRequest.of(0, 1));
            if (!recentCosts.isEmpty()) costPrice = recentCosts.get(0);
            lines.add(new SagaLine(productId, s.getQuantity(), soldRate, discount,
                    s.getTotalAmount(), s.getNetAmount(), s.getSrp(),
                    tax.rate(), tax.tax(), tax.gross(), catalogPrice, discountType, costPrice));
        }
        return lines;
    }

    /** The line's discount as an absolute amount. A "%"/"1" type is a percent of the line total; anything else is
     *  already an amount. Read from the line's stock (bsellDiscount + bsellDiscountType) — where the sell form binds
     *  the discount (the discounted net #sellrm is display-only and never submitted). */
    private BigDecimal resolveDiscount(SellDTO s, BigDecimal lineTotal) {
        var st = s.getStock();
        if (st == null || st.getBsellDiscount() == null) return BigDecimal.ZERO;
        BigDecimal d = st.getBsellDiscount();
        if (d.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        String type = st.getBsellDiscountType();
        if ("%".equals(type) || "1".equals(type)) {
            return lineTotal.multiply(d).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }
        return d;   // absolute amount
    }

    /** Human-readable discount type for the sell history table (Sell.dt). Blank when there's no discount so the
     *  column stays empty rather than labelling a zero discount. Mirrors resolveDiscount's %/amount decision. */
    private String resolveDiscountType(SellDTO s, BigDecimal resolvedDiscount) {
        if (resolvedDiscount == null || resolvedDiscount.compareTo(BigDecimal.ZERO) <= 0) return "";
        var st = s.getStock();
        String type = st != null ? st.getBsellDiscountType() : null;
        return ("%".equals(type) || "1".equals(type)) ? "%" : "Amount";
    }

    /** Turn the reserve's raw "product 891: only 7 sellable, 10 requested" reason into a name-resolved, cashier-
     *  friendly sentence. Falls back to a generic line when the reserve gave no detail. */
    private String friendlyOutOfStock(String reason, java.util.Map<Long, String> names) {
        if (reason == null || reason.isBlank()) return "Not enough sellable stock to complete the sale.";
        for (java.util.Map.Entry<Long, String> e : names.entrySet()) {
            reason = reason.replace("product " + e.getKey(), "'" + e.getValue() + "'");
        }
        return "Not enough sellable stock — " + reason
                + ". Expired or held stock is not sellable; add a fresh batch to sell more.";
    }

    private void safeRelease(String reservationId) {
        try {
            inventoryClient.release(reservationId);
        } catch (RuntimeException ignore) {
            LOG.warn("Compensating release failed for reservation {} (held stock will lapse/cleanup later)", reservationId);
        }
    }
}
