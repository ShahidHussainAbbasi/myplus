package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.PurchaseRecordRequest;
import com.myplus.commerce.contracts.dto.PurchaseRecordResult;
import com.myplus.commerce.contracts.dto.SaleRecordRequest;
import com.myplus.commerce.contracts.dto.SaleRecordResult;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Match;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignedStock;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceProductSource;
import com.myplus.marketplace.multiseller.entity.MarketplaceStockTransfer;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignedStockRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductSourceRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceStockTransferRepository;
import com.myplus.marketplace.support.AsOrg;
import com.myplus.marketplace.support.DownstreamMessage;

import lombok.RequiredArgsConstructor;

/**
 * MKT-3c (R20.4) — a seller sends stock to the MaxTheService warehouse, and MaxTheService BUYS it.
 *
 * <p>The seller offers a product MaxTheService also carries, a quantity and a price a unit. The warehouse receives it
 * (scanning each serial) or rejects it with a reason; the seller may cancel while it is still offered. On Receive the
 * books are written through the same two seams every channel uses, each in its own tenant:
 * <ol>
 *   <li>the SELLER's sale to MaxTheService ({@code /internal/sales}, as the seller): stock out, serials sold, an
 *       invoice owed by MaxTheService (no tender: a receivable);</li>
 *   <li>the WAREHOUSE's purchase from the seller ({@code /internal/purchases}, as the warehouse): stock in, serials
 *       registered, the bill owed to the seller.</li>
 * </ol>
 * If the purchase is refused after the sale was written, the sale is voided ({@code /internal/sales/reverse}) and the
 * transfer stays offered. The marketplace holds no stock and no money of its own here: only the link to both documents.
 *
 * <p>Not a marketplace sale: no commission, no settlement line, no payout. MaxTheService pays what it owes the seller
 * through the warehouse's payables, like any supplier.
 *
 * <p><b>MKT-5b — consigned instead of bought ({@code kind} CONSIGN).</b> Only under the pair's ACTIVE consignment
 * agreement ({@link ConsignmentAgreementService#activeFor}), checked when the seller sends and again when the warehouse
 * receives. The seller keeps owning the stock (R2.1, R4.5), so Receive writes no sale and no purchase: it sets the
 * stock aside in the SELLER's books with a hold that never lapses ({@code HoldKind.CONSIGNMENT}), so the shop can no
 * longer sell it while it sits at the warehouse, and records the units in the consigned stock ledger
 * ({@code mkt_consigned_stock}). Selling it, counting it and returning it are later slices.
 */
@Service
@RequiredArgsConstructor
public class StockTransferService {

    private static final Logger LOG = LoggerFactory.getLogger(StockTransferService.class);

    static final int MAX_QUANTITY = 10_000;
    static final int PAGE = 100;
    /** The contact on the seller's customer record of MaxTheService (the books refuse a customer without one). */
    static final String BUYER_CONTACT = "MaxTheService warehouse";

    private final MarketplaceStockTransferRepository transfers;
    private final MarketplaceProductSourceRepository sources;
    private final MarketplaceProductRepository products;
    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplaceSettingsService settings;
    private final MarketplaceSellerService sellers;
    private final SellerAccess access;
    private final TradeClient trade;
    private final CatalogClient catalog;
    private final MarketplaceAuditService audit;
    private final ConsignmentAgreementService consignments;
    private final MarketplaceConsignedStockRepository consigned;

    // ── both sides ────────────────────────────────────────────────────────────────────────────────────

    public TransferDTOs.View view() {
        sellers.assertActiveSeller();
        Long org = access.org();
        Long warehouse = settings.warehouseOrg().orElse(null);
        if (warehouse == null) return new TransferDTOs.View(false, false, List.of(), List.of());
        if (warehouse.equals(org)) {
            List<MarketplaceStockTransfer> in = transfers.findByWarehouseOrgIdOrderByIdDesc(org, PageRequest.of(0, PAGE)).getContent();
            Map<Long, Boolean> serial = serialRequired(in);
            Map<Long, String> names = new HashMap<>();
            return new TransferDTOs.View(true, true, List.of(), in.stream()
                    .map(t -> toDto(t, names.computeIfAbsent(t.getSellerOrgId(), this::sellerName),
                            Boolean.TRUE.equals(serial.get(t.getId()))))
                    .toList(), null, held(org, names));
        }
        List<MarketplaceStockTransfer> out = transfers.findBySellerOrgIdOrderByIdDesc(org, PageRequest.of(0, PAGE)).getContent();
        String under = consignments.activeFor(org, warehouse).map(MarketplaceConsignmentAgreement::getAgreementNo).orElse(null);
        return new TransferDTOs.View(false, true, carried(org, warehouse), out.stream()
                .map(t -> toDto(t, null, false)).toList(), under, held(org, new HashMap<>()));
    }

    /** MKT-5b — the consigned stock ledger summed per owner and product, for either side of the pair. */
    List<TransferDTOs.Held> held(Long org, Map<Long, String> names) {
        List<TransferDTOs.Held> out = new ArrayList<>();
        for (Object[] r : consigned.heldFor(org)) {
            Long owner = (Long) r[0];
            out.add(new TransferDTOs.Held(names.computeIfAbsent(owner, this::sellerName), (Long) r[1], (String) r[2],
                    ((Number) r[3]).longValue(), r[4] == null ? BigDecimal.ZERO : new BigDecimal(r[4].toString()).setScale(2, RoundingMode.HALF_UP)));
        }
        return out;
    }

    // ── the seller ────────────────────────────────────────────────────────────────────────────────────

    public TransferDTOs.Transfer offer(TransferDTOs.OfferRequest req) {
        sellers.assertActiveSeller();
        Long org = access.org();
        Long warehouse = settings.warehouseOrg()
                .orElseThrow(() -> new ValidationException("MaxTheService has no warehouse to send stock to."));
        if (warehouse.equals(org)) throw new ValidationException("The warehouse cannot send stock to itself.");
        if (req == null || req.mktProductId() == null) throw new ValidationException("Choose the product.");
        int qty = req.quantity() == null ? 0 : req.quantity();
        if (qty < 1 || qty > MAX_QUANTITY) throw new ValidationException("Enter how many you are sending (1 to " + MAX_QUANTITY + ").");
        String kind = kindOf(req.kind());
        boolean consign = MarketplaceStockTransfer.CONSIGN.equals(kind);
        MarketplaceConsignmentAgreement agreement = !consign ? null : consignments.activeFor(org, warehouse)
                .orElseThrow(() -> new ValidationException("You have no active consignment agreement with MaxTheService. "
                        + "Accept its terms under Consignment agreements first, or sell the stock to MaxTheService instead."));
        BigDecimal price = req.unitPrice();
        if (price == null || price.signum() <= 0)
            throw new ValidationException(consign ? "Enter the value of one unit." : "Enter the price of one unit.");
        if (price.scale() > 2) throw new ValidationException("A price has at most two decimal places.");

        MarketplaceProductSource mine = matched(org, req.mktProductId())
                .orElseThrow(() -> new ValidationException("Choose one of your own products that MaxTheService has matched."));
        MarketplaceProductSource theirs = matched(warehouse, req.mktProductId())
                .orElseThrow(() -> new ValidationException("MaxTheService does not carry this product yet, so it cannot "
                        + "receive it. Ask MaxTheService to add it first."));

        MarketplaceStockTransfer t = new MarketplaceStockTransfer();
        t.setSellerOrgId(org);
        t.setWarehouseOrgId(warehouse);
        t.setMktProductId(req.mktProductId());
        t.setSellerProductId(mine.getSourceProductId());
        t.setWarehouseProductId(theirs.getSourceProductId());
        t.setProductName(productName(req.mktProductId(), mine));
        t.setQuantity(qty);
        t.setUnitPrice(price.setScale(2, RoundingMode.UNNECESSARY));
        t.setNote(clip(req.note(), 500));
        t.setStatus(MarketplaceStockTransfer.OFFERED);
        t.setKind(kind);
        t.setAgreementId(agreement == null ? null : agreement.getId());
        t.setOfferedByUserId(access.userId());
        t = transfers.save(t);
        audit.event(consign ? "MKT_CONSIGN_OFFERED" : "MKT_TRANSFER_OFFERED", "MKT_STOCK_TRANSFER", t.getTransferNo(), org, MarketplaceAuditService.Actor.SELLER,
                null, MarketplaceStockTransfer.OFFERED, total(t), t.getQuantity() + " × " + t.getProductName());
        return toDto(t, null, false);
    }

    public TransferDTOs.Transfer cancel(TransferDTOs.DecisionRequest req) {
        sellers.assertActiveSeller();
        MarketplaceStockTransfer t = load(req);
        if (!t.getSellerOrgId().equals(access.org())) throw notFound();
        decide(t, MarketplaceStockTransfer.CANCELLED, null);
        audit.event("MKT_TRANSFER_CANCELLED", "MKT_STOCK_TRANSFER", t.getTransferNo(), t.getSellerOrgId(),
                MarketplaceAuditService.Actor.SELLER, MarketplaceStockTransfer.OFFERED, MarketplaceStockTransfer.CANCELLED, null, null);
        return toDto(t, null, false);
    }

    // ── the warehouse ─────────────────────────────────────────────────────────────────────────────────

    public TransferDTOs.Transfer reject(TransferDTOs.DecisionRequest req) {
        sellers.assertActiveSeller();
        MarketplaceStockTransfer t = load(req);
        if (!t.getWarehouseOrgId().equals(access.org())) throw notFound();
        String reason = clip(req.reason(), 500);
        if (reason == null) throw new ValidationException("Tell the seller why it is not taken.");
        decide(t, MarketplaceStockTransfer.REJECTED, reason);
        audit.event("MKT_TRANSFER_REJECTED", "MKT_STOCK_TRANSFER", t.getTransferNo(), t.getSellerOrgId(),
                MarketplaceAuditService.Actor.SELLER, MarketplaceStockTransfer.OFFERED, MarketplaceStockTransfer.REJECTED, null, reason);
        return toDto(t, sellerName(t.getSellerOrgId()), false);
    }

    /**
     * Received at the warehouse: the seller's sale, then the warehouse's purchase, then the row. See the class note on
     * why a refused purchase voids the sale, and {@link MarketplaceStockTransfer#getAttempt()} on the keys.
     */
    public TransferDTOs.Transfer receive(TransferDTOs.DecisionRequest req) {
        sellers.assertActiveSeller();
        MarketplaceStockTransfer loaded = load(req);
        Long warehouse = access.org();
        if (!loaded.getWarehouseOrgId().equals(warehouse)) throw notFound();
        if (!MarketplaceStockTransfer.RECEIVING.equals(loaded.getStatus())) assertOffered(loaded);
        List<String> serials = cleanSerials(req.serials());
        boolean needSerials = Boolean.TRUE.equals(serialRequired(List.of(loaded)).get(loaded.getId()));
        if (needSerials && serials.size() != loaded.getQuantity())
            throw new ValidationException("Scan the serial number (IMEI) of each unit received: " + loaded.getQuantity()
                    + " for " + loaded.getProductName() + ".");
        if (!needSerials && !serials.isEmpty() && serials.size() != loaded.getQuantity())
            throw new ValidationException("Enter one serial number for each of the " + loaded.getQuantity() + " units, or none.");
        String joined = serials.isEmpty() ? null : String.join(",", serials);
        if (joined != null && joined.length() > 2000) throw new ValidationException("Too many serial numbers for one transfer.");

        boolean consign = MarketplaceStockTransfer.CONSIGN.equals(loaded.getKind());
        MarketplaceConsignmentAgreement agreement = !consign ? null : consignments.activeFor(loaded.getSellerOrgId(), warehouse)
                .orElseThrow(() -> new ValidationException("There is no active consignment agreement with this seller any "
                        + "more, so this stock cannot be taken on consignment. Choose Not taken and say why."));

        // Claim it first: a cancel or reject racing this Receive now loses on the row's version instead of leaving both
        // books written under a cancelled transfer. A Receive interrupted after this point is retried from RECEIVING
        // with the same keys, so it replays rather than books twice.
        loaded.setStatus(MarketplaceStockTransfer.RECEIVING);
        MarketplaceStockTransfer t = transfers.saveAndFlush(loaded);
        if (consign) return receiveConsigned(t, agreement, joined, needSerials);
        String key = "MKT-" + t.getTransferNo() + "-" + t.getAttempt();
        String sellerName = sellerName(t.getSellerOrgId());
        SaleRecordResult sale;
        try {
            sale = AsOrg.call(t.getSellerOrgId(), () -> trade.recordSale(SaleRecordRequest.builder()
                .idempotencyKey(key + "-sale")
                .organizationId(t.getSellerOrgId())
                .channel("MARKETPLACE")
                // the seller's customer record of MaxTheService: found again by name and contact on every transfer, never
                // a new row each time (the sale path's own match); the books need a contact, and there is no phone to give
                .customer(SaleRecordRequest.Customer.builder().name(PlatformWarehouseService.NAME).contact(BUYER_CONTACT).build())
                .lines(List.of(SaleRecordRequest.Line.builder()
                        .productId(t.getSellerProductId())
                        .quantity(BigDecimal.valueOf(t.getQuantity()))
                        .unitPrice(t.getUnitPrice())
                        .description(t.getProductName())
                        .serials(joined)
                        .build()))
                .notes("Stock sent to the MaxTheService warehouse, transfer " + t.getTransferNo())
                .build()));
        } catch (RuntimeException e) {
            // Same attempt: if the sale did go through and only the answer was lost, the retry replays it.
            back(t, false);
            throw new ValidationException("The seller's sale could not be recorded, so nothing was received. "
                    + DownstreamMessage.orElse(e, "Please try again."));
        }
        if (sale == null || sale.getInvoiceNo() == null) {
            back(t, false);
            throw new ValidationException("The seller's sale could not be recorded, so nothing was received. Please try again.");
        }

        PurchaseRecordResult bought;
        try {
            // NOT AsOrg: the warehouse IS the person receiving, so the call carries their own identity, and with it their
            // X-Org-Caps. Under AsOrg the purchase path would read the warehouse's capabilities from business-service's
            // local store, which does not hold the tenant's shape, and demand an expiry date the warehouse never switched on.
            bought = trade.recordPurchase(PurchaseRecordRequest.builder()
                    .idempotencyKey(key + "-purchase")
                    .organizationId(warehouse)
                    .vendorName(sellerName)
                    .productId(t.getWarehouseProductId())
                    .quantity(BigDecimal.valueOf(t.getQuantity()))
                    .unitCost(t.getUnitPrice())
                    .serials(joined)
                    .billNo(sale.getInvoiceNo())
                    .build());
            if (bought == null || bought.getPurchaseId() == null) throw new ValidationException("No purchase was returned.");
        } catch (RuntimeException e) {
            // The seller's books must not keep a sale the warehouse never bought: void it, then back to OFFERED on the next
            // attempt (the old sale key would replay the voided invoice). If the void itself fails, the transfer stays
            // RECEIVING: a retry replays the live sale and tries the purchase again.
            LOG.warn("MKT-3c: purchase for {} refused, voiding seller invoice {}: {}", t.getTransferNo(), sale.getInvoiceNo(), e.toString());
            AsOrg.run(t.getSellerOrgId(), () -> trade.reverseSale(sale.getInvoiceNo(), "Transfer " + t.getTransferNo() + " not received"));
            back(t, true);
            throw new ValidationException("The warehouse's purchase could not be recorded, so nothing was received and the "
                    + "seller's sale was cancelled. " + DownstreamMessage.orElse(e, e.getMessage() == null ? "" : e.getMessage()));
        }

        t.setSerials(joined);
        t.setSaleInvoiceNo(sale.getInvoiceNo());
        t.setPurchaseId(bought.getPurchaseId());
        t.setStatus(MarketplaceStockTransfer.RECEIVED);
        t.setDecidedByUserId(access.userId());
        t.setDecidedAt(LocalDateTime.now());
        MarketplaceStockTransfer done = transfers.save(t);
        audit.event("MKT_TRANSFER_RECEIVED", "MKT_STOCK_TRANSFER", done.getTransferNo(), done.getSellerOrgId(),
                MarketplaceAuditService.Actor.SELLER, MarketplaceStockTransfer.OFFERED, MarketplaceStockTransfer.RECEIVED,
                total(done), "seller invoice " + sale.getInvoiceNo() + ", warehouse purchase " + bought.getPurchaseId());
        return toDto(done, sellerName, needSerials);
    }

    /**
     * MKT-5b — received on consignment: the stock is set aside in the seller's books (it stays theirs), then counted
     * into the ledger, then the row. The hold is idempotent on its key and the ledger on (transfer, RECEIVED), so a
     * Receive retried from RECEIVING replays both instead of setting aside or counting twice.
     */
    private TransferDTOs.Transfer receiveConsigned(MarketplaceStockTransfer t, MarketplaceConsignmentAgreement agreement,
                                                   String joined, boolean needSerials) {
        String holdKey = "MKT-" + t.getTransferNo() + "-" + t.getAttempt() + "-consign";
        StockHoldRequest hold = StockHoldRequest.builder()
                .organizationId(t.getSellerOrgId())
                .holdKey(holdKey)
                .lines(List.of(new StockReservationLine(t.getSellerProductId(), BigDecimal.valueOf(t.getQuantity()))))
                .holdKind(StockReservationRequest.HoldKind.CONSIGNMENT)
                .build();
        StockHoldResponse r;
        try {
            r = AsOrg.call(t.getSellerOrgId(), () -> trade.holdStock(hold));
        } catch (RuntimeException e) {
            back(t, false);
            throw new ValidationException("The seller's stock could not be set aside, so nothing was received. "
                    + DownstreamMessage.orElse(e, "Please try again."));
        }
        if (r == null || !r.isHeld()) {
            back(t, false);
            throw new ValidationException("The seller's books do not show " + t.getQuantity() + " × " + t.getProductName()
                    + " free to set aside, so nothing was received"
                    + (r == null || r.getReason() == null ? "." : ": " + r.getReason()));
        }

        if (!consigned.existsByTransferIdAndMovement(t.getId(), MarketplaceConsignedStock.RECEIVED)) {
            MarketplaceConsignedStock m = new MarketplaceConsignedStock();
            m.setAgreementId(agreement.getId());
            m.setConsignorOrgId(t.getSellerOrgId());
            m.setConsigneeOrgId(t.getWarehouseOrgId());
            m.setMktProductId(t.getMktProductId());
            m.setConsignorProductId(t.getSellerProductId());
            m.setProductName(t.getProductName());
            m.setMovement(MarketplaceConsignedStock.RECEIVED);
            m.setQuantity(t.getQuantity());
            m.setUnitValue(t.getUnitPrice());
            m.setSerials(joined);
            m.setTransferId(t.getId());
            m.setHoldKey(holdKey);
            m.setCreatedByUserId(access.userId());
            consigned.save(m);
        }

        t.setSerials(joined);
        t.setHoldKey(holdKey);
        t.setAgreementId(agreement.getId());
        t.setStatus(MarketplaceStockTransfer.RECEIVED);
        t.setDecidedByUserId(access.userId());
        t.setDecidedAt(LocalDateTime.now());
        MarketplaceStockTransfer done = transfers.save(t);
        audit.event("MKT_CONSIGN_RECEIVED", "MKT_STOCK_TRANSFER", done.getTransferNo(), done.getSellerOrgId(),
                MarketplaceAuditService.Actor.SELLER, MarketplaceStockTransfer.OFFERED, MarketplaceStockTransfer.RECEIVED,
                total(done), "consigned under " + agreement.getAgreementNo() + ", hold " + holdKey);
        return toDto(done, sellerName(done.getSellerOrgId()), needSerials);
    }

    /** Nothing was received: OFFERED again, on the next attempt when a sale was voided. */
    private void back(MarketplaceStockTransfer t, boolean nextAttempt) {
        t.setStatus(MarketplaceStockTransfer.OFFERED);
        if (nextAttempt) t.setAttempt(t.getAttempt() + 1);
        transfers.save(t);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────

    /** The seller's matched products that the warehouse has matched too, by name. */
    List<TransferDTOs.Product> carried(Long org, Long warehouse) {
        List<TransferDTOs.Product> out = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (MarketplaceProductSource s : sources.findByOrganizationIdOrderByCreatedAtDesc(org, PageRequest.of(0, 500)).getContent()) {
            if (!Match.MATCHED.name().equals(s.getMatchStatus()) || s.getMktProductId() == null || !seen.add(s.getMktProductId()))
                continue;
            if (matched(warehouse, s.getMktProductId()).isPresent())
                out.add(new TransferDTOs.Product(s.getMktProductId(), productName(s.getMktProductId(), s)));
        }
        out.sort(java.util.Comparator.comparing(TransferDTOs.Product::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private java.util.Optional<MarketplaceProductSource> matched(Long org, Long mktProductId) {
        return sources.findFirstByOrganizationIdAndMktProductIdAndMatchStatus(org, mktProductId, Match.MATCHED.name());
    }

    private String productName(Long mktProductId, MarketplaceProductSource fallback) {
        return products.findById(mktProductId).map(MarketplaceProduct::getCanonicalName).filter(n -> n != null && !n.isBlank())
                .orElse(fallback.getSourceProductName() == null ? "Product " + mktProductId : fallback.getSourceProductName());
    }

    /** Whether each OFFERED transfer's product is serial-tracked, read from the seller's catalogue (as the seller). */
    Map<Long, Boolean> serialRequired(List<MarketplaceStockTransfer> ts) {
        Map<Long, Boolean> out = new HashMap<>();
        Map<Long, List<MarketplaceStockTransfer>> bySeller = ts.stream()
                .filter(t -> MarketplaceStockTransfer.OFFERED.equals(t.getStatus()))
                .collect(Collectors.groupingBy(MarketplaceStockTransfer::getSellerOrgId));
        bySeller.forEach((seller, list) -> {
            List<Long> ids = list.stream().map(MarketplaceStockTransfer::getSellerProductId).distinct().toList();
            List<ProductRef> refs = AsOrg.call(seller, () -> catalog.getProductsFresh(ids, true));
            Map<Long, Boolean> byProduct = new HashMap<>();
            if (refs != null) for (ProductRef r : refs) byProduct.put(r.getId(), Boolean.TRUE.equals(r.getRequiresSerial()));
            for (MarketplaceStockTransfer t : list) out.put(t.getId(), Boolean.TRUE.equals(byProduct.get(t.getSellerProductId())));
        });
        return out;
    }

    private MarketplaceStockTransfer load(TransferDTOs.DecisionRequest req) {
        if (req == null || req.transferNo() == null) throw new ValidationException("Choose the transfer.");
        String no = req.transferNo().trim().toUpperCase(Locale.ROOT);
        if (!no.matches("ST-\\d{1,12}")) throw notFound();
        MarketplaceStockTransfer t = transfers.findById(Long.valueOf(no.substring(3))).orElseThrow(StockTransferService::notFound);
        if (req.version() != null && !Objects.equals(req.version(), t.getVersion()))
            throw new ValidationException("This transfer changed meanwhile. Refresh and try again.");
        return t;
    }

    private void decide(MarketplaceStockTransfer t, String to, String reason) {
        assertOffered(t);
        t.setStatus(to);
        t.setRejectReason(reason);
        t.setDecidedByUserId(access.userId());
        t.setDecidedAt(LocalDateTime.now());
        transfers.save(t);
    }

    private static void assertOffered(MarketplaceStockTransfer t) {
        if (!MarketplaceStockTransfer.OFFERED.equals(t.getStatus()))
            throw new ValidationException("This transfer is already " + t.getStatus().toLowerCase(Locale.ROOT) + ".");
    }

    /** BUY when not given (every 3c caller), else BUY or CONSIGN. */
    static String kindOf(String kind) {
        String k = kind == null || kind.isBlank() ? MarketplaceStockTransfer.BUY : kind.trim().toUpperCase(Locale.ROOT);
        if (!MarketplaceStockTransfer.BUY.equals(k) && !MarketplaceStockTransfer.CONSIGN.equals(k))
            throw new ValidationException("Choose whether MaxTheService buys this stock or holds it on consignment.");
        return k;
    }

    private static ValidationException notFound() {
        return new ValidationException("Transfer not found.");
    }

    String sellerName(Long org) {
        return accounts.findByOrganizationId(org).map(a -> a.getDisplayName()).filter(n -> n != null && !n.isBlank())
                .orElse("Seller " + org);
    }

    static List<String> cleanSerials(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim());
        if (out.stream().distinct().count() != out.size()) throw new ValidationException("The same serial number is entered twice.");
        return out;
    }

    private static BigDecimal total(MarketplaceStockTransfer t) {
        return t.getUnitPrice().multiply(BigDecimal.valueOf(t.getQuantity()));
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static TransferDTOs.Transfer toDto(MarketplaceStockTransfer t, String sellerName, boolean serialRequired) {
        return new TransferDTOs.Transfer(t.getTransferNo(), t.getProductName(), t.getQuantity(), t.getUnitPrice(), total(t),
                t.getNote(), t.getStatus(), t.getRejectReason(), sellerName, serialRequired, t.getSaleInvoiceNo(),
                t.getPurchaseId(), t.getCreatedAt(), t.getDecidedAt(), t.getVersion(), t.getKind(),
                t.getAgreementId() == null ? null : String.format("CA-%06d", t.getAgreementId()));
    }
}
