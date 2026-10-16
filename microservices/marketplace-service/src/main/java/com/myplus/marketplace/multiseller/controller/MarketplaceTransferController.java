package com.myplus.marketplace.multiseller.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceStockTransfer;
import com.myplus.marketplace.multiseller.service.PlatformWarehouseService;
import com.myplus.marketplace.multiseller.service.ConsignedSaleService;
import com.myplus.marketplace.multiseller.service.ShrinkageService;
import com.myplus.marketplace.multiseller.service.StockTransferService;

import lombok.RequiredArgsConstructor;

/**
 * MKT-3c — stock a seller sends to the MaxTheService warehouse. Rules live in {@link StockTransferService}.
 *
 * <pre>
 *   GET  /mkt/seller/transfers            the warehouse: what was sent to it; any other seller: what it can send, and its own
 *   POST /mkt/seller/transfers            seller: {mktProductId, quantity, unitPrice, note, kind BUY|CONSIGN (MKT-5b)}
 *   POST /mkt/seller/transfers/cancel     seller: {transferNo, version}
 *   POST /mkt/seller/transfers/reject     warehouse: {transferNo, version, reason}
 *   POST /mkt/seller/transfers/receive    warehouse: {transferNo, version, serials[]}
 *   POST /mkt/seller/consigned/list       MKT-5c, whoever the agreement lets set the price: {consignorOrgId (warehouse
 *                                         only), mktProductId, price, deliveryAreas, promiseHours, warrantyPolicyId,
 *                                         returnPolicyId, version}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceTransferController {

    private final StockTransferService transfers;
    private final ConsignedSaleService consigned;
    private final ShrinkageService shrinkage;

    @GetMapping("/mkt/seller/transfers")
    public ApiResponse<TransferDTOs.View> view() {
        return ApiResponse.success(shrinkage.decorate(transfers.view()));
    }

    @PostMapping("/mkt/seller/transfers")
    public ApiResponse<TransferDTOs.Transfer> offer(@RequestBody(required = false) TransferDTOs.OfferRequest body) {
        TransferDTOs.Transfer t = transfers.offer(body);
        if (MarketplaceStockTransfer.CONSIGN.equals(t.kind()))
            return ApiResponse.success(t, "Sent to " + PlatformWarehouseService.NAME + " on consignment as " + t.transferNo()
                    + ", under " + t.agreementNo() + ". It stays yours, on the warehouse's shelf once received.");
        return ApiResponse.success(t, "Sent to " + PlatformWarehouseService.NAME + " as " + t.transferNo()
                + ". It is yours until the warehouse receives it.");
    }

    @PostMapping("/mkt/seller/transfers/cancel")
    public ApiResponse<TransferDTOs.Transfer> cancel(@RequestBody(required = false) TransferDTOs.DecisionRequest body) {
        return ApiResponse.success(transfers.cancel(body), "Cancelled.");
    }

    @PostMapping("/mkt/seller/transfers/reject")
    public ApiResponse<TransferDTOs.Transfer> reject(@RequestBody(required = false) TransferDTOs.DecisionRequest body) {
        return ApiResponse.success(transfers.reject(body), "Not taken. The seller sees your reason.");
    }

    @PostMapping("/mkt/seller/transfers/receive")
    public ApiResponse<TransferDTOs.Transfer> receive(@RequestBody(required = false) TransferDTOs.DecisionRequest body) {
        TransferDTOs.Transfer t = transfers.receive(body);
        if (MarketplaceStockTransfer.CONSIGN.equals(t.kind()))
            return ApiResponse.success(t, "Received on consignment under " + t.agreementNo() + ". It is " + t.sellerName()
                    + "'s stock on your shelf: nothing is owed until it sells.");
        return ApiResponse.success(t, "Received. It is in your stock, and you owe " + t.sellerName() + " Rs "
                + String.format(java.util.Locale.ROOT, "%,.2f", t.total()) + ".");
    }

    @PostMapping("/mkt/seller/consigned/list")
    public ApiResponse<TransferDTOs.Listing> list(@RequestBody(required = false) TransferDTOs.ListRequest body) {
        TransferDTOs.Listing l = consigned.list(body);
        return ApiResponse.success(l, "Listed for sale by " + PlatformWarehouseService.NAME + " at Rs "
                + String.format(java.util.Locale.ROOT, "%,.2f", l.price()) + ", under " + l.agreementNo()
                + ". Shoppers see " + l.available() + " available.");
    }

    // ── MKT-5d: counts and shrinkage claims ───────────────────────────────────────────────────────────

    @PostMapping("/mkt/seller/consigned/count")
    public ApiResponse<TransferDTOs.CountRow> count(@RequestBody(required = false) TransferDTOs.CountRequest body) {
        TransferDTOs.CountRow c = shrinkage.count(body);
        String head = "Counted " + c.counted() + " of " + c.expected() + " (" + c.countNo() + "): ";
        if (c.counted() > c.expected())
            return ApiResponse.success(c, head + (c.counted() - c.expected()) + " more than held. Recorded; nothing moves.");
        if (c.missing() == 0) return ApiResponse.success(c, head + "the count matches.");
        return ApiResponse.success(c, head + c.missing() + " missing, no longer sold"
                + (c.tolerated() > 0 ? ", " + c.tolerated() + " within the agreement's tolerance" : "")
                + ". The owner can write them off" + (c.missing() > c.tolerated() ? " or claim them" : "") + ".");
    }

    @PostMapping("/mkt/seller/consigned/claim")
    public ApiResponse<TransferDTOs.ClaimRow> claim(@RequestBody(required = false) TransferDTOs.ClaimRequest body) {
        TransferDTOs.ClaimRow c = shrinkage.claim(body);
        return ApiResponse.success(c, "Claimed as " + c.claimNo() + ": Rs " + String.format(java.util.Locale.ROOT, "%,.2f", c.amount())
                + " for " + c.units() + " × " + c.productName() + ". Nothing is paid until " + PlatformWarehouseService.NAME
                + " decides.");
    }

    /** WRITE_OFF or WITHDRAW (the owner). */
    @PostMapping("/mkt/seller/consigned/shrinkage")
    public ApiResponse<TransferDTOs.CountRow> ownerDecision(@RequestBody(required = false) TransferDTOs.ShrinkDecision body) {
        if (body != null && body.action() != null && !body.action().equalsIgnoreCase("WRITE_OFF")
                && !body.action().equalsIgnoreCase("WITHDRAW"))
            throw new com.myplus.common.web.exception.ValidationException("Choose what to do.");
        TransferDTOs.CountRow c = shrinkage.decide(body);
        return ApiResponse.success(c, "WITHDRAW".equalsIgnoreCase(body.action()) ? "Claim withdrawn."
                : "Written off your books: the missing units of " + c.countNo() + " (" + c.writtenOff() + " in all).");
    }

    @GetMapping("/mkt/operator/shrinkage-claims")
    public ApiResponse<java.util.List<TransferDTOs.ClaimRow>> claims() {
        return ApiResponse.success(shrinkage.queue());
    }

    /** APPROVE or REJECT (the operator). */
    @PostMapping("/mkt/operator/shrinkage-claims/decision")
    public ApiResponse<TransferDTOs.CountRow> operatorDecision(@RequestBody(required = false) TransferDTOs.ShrinkDecision body) {
        if (body != null && body.action() != null && !body.action().equalsIgnoreCase("APPROVE")
                && !body.action().equalsIgnoreCase("REJECT"))
            throw new com.myplus.common.web.exception.ValidationException("Choose approve or reject.");
        TransferDTOs.CountRow c = shrinkage.decide(body);
        return ApiResponse.success(c, "APPROVE".equalsIgnoreCase(body.action())
                ? "Approved. The owner is credited and the units are written off its books."
                : "Rejected. The owner sees your reason and can write the units off.");
    }
}
