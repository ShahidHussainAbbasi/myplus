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

    @org.springframework.beans.factory.annotation.Autowired
    private GlOutboxService glOutboxService;   // #4: durable GL posting via the outbox (replaces direct FinanceClient)

    @org.springframework.beans.factory.annotation.Autowired
    private AuditService auditService;   // #6: append-only audit trail

    @org.springframework.beans.factory.annotation.Autowired
    private PeriodLockGuard periodLockGuard;   // period close: reject a sale dated in a closed period

    @org.springframework.beans.factory.annotation.Autowired
    private StoreCreditService storeCreditService;   // SF-5 Model B: redeem store credit at checkout

    @org.springframework.beans.factory.annotation.Autowired
    private com.myplus.common.settings.SettingsService settingsService;   // B1: per-org pharmacy rx policy

    /** Cap a STORE_CREDIT tender to the customer's balance (never trust the client / overdraw). Mutates the tender
     *  amount in the dto so settle uses the real value; returns the amount that will be redeemed (0 if none / no
     *  identified customer / zero balance). */
    private BigDecimal capStoreCreditTender(com.myplus.business_service.dto.CustomerHistoryDTO dto, Long customerId) {
        if (dto.getTenders() == null) return BigDecimal.ZERO;
        for (com.myplus.business_service.dto.TenderDTO t : dto.getTenders()) {
            if (t != null && "STORE_CREDIT".equalsIgnoreCase(t.getMethod())) {
                BigDecimal want = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
                BigDecimal cap = (customerId != null && want.signum() > 0)
                        ? want.min(storeCreditService.balance(customerId)) : BigDecimal.ZERO;
                t.setAmount(cap);   // settle counts STORE_CREDIT as paid → the capped value is the real paid amount
                return cap;
            }
        }
        return BigDecimal.ZERO;
    }

    /** @return the invoice number of the recorded sale. */
    public String addSell(CustomerHistoryDTO dto) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        periodLockGuard.assertOpen(java.time.LocalDate.now());   // period close: a new sale is a today-dated entry

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

        // Store credit (SF-5 Model B): a STORE_CREDIT tender is capped to the customer's balance BEFORE settling
        // (never trust the client amount, never overdraw). Requires an identified (existing) customer — a fresh
        // customer has no credit. The tender is settled at the capped amount; we redeem exactly that after the write.
        Long scCustomerId = (dto.getCustomer() != null) ? dto.getCustomer().getCustomerId() : null;
        BigDecimal scRedeem = capStoreCreditTender(dto, scCustomerId);

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

        // Store credit (SF-5 Model B): redeem the capped amount from the customer's balance now that the sale is
        // written (ledger −amount + cached balance). Best-effort — the sale is already recorded.
        if (scRedeem != null && scRedeem.signum() > 0 && scCustomerId != null) {
            try { storeCreditService.redeem(scCustomerId, scRedeem, ch.getInvoiceNo()); }
            catch (Exception ex) { LOG.warn("store-credit redeem failed for {} (sale recorded)", ch.getInvoiceNo(), ex); }
        }

        // F3b: auto-post the sale to the General Ledger (Dr Cash/AR, Cr Sales+Tax; + COGS from the line cost).
        // Best-effort — a GL hiccup must never fail the sale (reconcile later). Only on a NEW sale (not edits).
        try {
            BigDecimal cost = BigDecimal.ZERO;
            for (SagaLine l : lines)
                if (l.costPrice() != null) cost = cost.add(l.costPrice().multiply(BigDecimal.valueOf(l.quantity())));
            glOutboxService.enqueue(com.myplus.commerce.contracts.dto.PostingEventRequest.builder()
                    .eventType("SALE").date(java.time.LocalDate.now()).ref(ch.getInvoiceNo())
                    .grandTotal(ch.getGrandTotal()).subTotal(ch.getSubTotal()).taxTotal(ch.getTaxTotal())
                    .cost(cost).paidAmount(ch.getPaidAmount()).method(ch.getPaymentMode())
                    .storeCredit(scRedeem).build())    // store-credit portion → Dr 2200 (not Cash)
                    ;
        } catch (Exception ex) {
            LOG.warn("GL enqueue failed for sale {} (sale recorded)", ch.getInvoiceNo(), ex);
        }

        // #6: append-only audit of the sale (who/when/what/ref/amount).
        auditService.record("SALE", "INVOICE", ch.getInvoiceNo(), ch.getGrandTotal(), "items=" + lines.size());
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
        // B1: read the pharmacy policy ONCE per sale, not per line. Inert for a non-pharmacy tenant — no product
        // of theirs carries the flag, so the check below never fires.
        boolean requireRx = settingsService.getBool("pharmacy.rx.requirePrescription");
        List<SagaLine> lines = new ArrayList<>();
        for (SellDTO s : dto.getSales()) {
            // M4e (slice 101): productId-native — every caller (POS + pharmacy) submits productId now.
            Long productId = s.getProductId();
            if (productId == null) throw new RuntimeException("Sale line has no productId — submit productId-native.");
            ProductRef product = catalogClient.getProduct(productId);
            String pName = (product != null && product.getName() != null) ? product.getName()
                    : (s.getItemName() != null ? s.getItemName() : ("product " + productId));
            // B1: a prescription-only medicine may not leave the counter on a sale that declares no prescription.
            // Server-side and privilege-independent — this is a clinical rule, not a permission, so it binds a
            // super-user too. Costs nothing extra: the flag rides on the ref this loop already fetched.
            if (requireRx && product != null && Boolean.TRUE.equals(product.getRxRequired())
                    && dto.getPrescriptionId() == null) {
                throw new com.myplus.common.web.exception.ValidationException(pName
                        + " is prescription-only — start this sale from the prescription (Dispense), or record the"
                        + " prescription first.");
            }
            if (productNames != null) productNames.put(productId, pName);
            BigDecimal catalogPrice = (product != null && product.getSellingPrice() != null)
                    ? product.getSellingPrice() : BigDecimal.ZERO;
            // The rate this line SOLD at = the cashier's rate (may override catalog); fall back to catalog. The
            // catalog price is snapshotted separately so reports show BOTH "catalog price" and "sold at".
            BigDecimal soldRate = (s.getSellRate() != null && s.getSellRate().compareTo(BigDecimal.ZERO) > 0)
                    ? s.getSellRate() : catalogPrice;
            BigDecimal productTaxRate = product != null ? product.getTaxRate() : null;
            // The line total is DERIVED here — qty × the rate this line sold at — never taken from the client.
            // Trusting the submitted totalAmount is how a line came to be stored as rate=1000 (catalog fallback)
            // with total=850 (the cashier's real price): two numbers from different sources, contradicting each
            // other, and no way for the row to be right. Derive it and the contradiction cannot be persisted.
            BigDecimal qty = s.getQuantity() != null ? BigDecimal.valueOf(s.getQuantity()) : BigDecimal.ONE;
            BigDecimal lineTotal = soldRate.multiply(qty).setScale(2, java.math.RoundingMode.HALF_UP);
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
            // totalAmount = qty × sold rate (before discount/tax). netAmount = what the customer is actually
            // charged for this line (discounted base + tax) — DERIVED, not the client's figure. The sell form
            // posts its "Net Amount" box, which actually holds PROFIT (total − cost×qty − discount); persisting
            // that made Sell.netAmount mean three different things and made the margin report — which computes
            // netAmount − cost×qty — subtract the cost twice whenever a purchase rate was present.
            lines.add(new SagaLine(productId, s.getQuantity(), soldRate, discount,
                    lineTotal, tax.gross(), s.getSrp(),
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
