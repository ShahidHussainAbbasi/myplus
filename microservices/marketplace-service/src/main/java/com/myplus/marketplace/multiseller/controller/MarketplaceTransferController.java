package com.myplus.marketplace.multiseller.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.multiseller.dto.TransferDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceStockTransfer;
import com.myplus.marketplace.multiseller.service.PlatformWarehouseService;
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
 * </pre>
 */
@RestController
@RequiredArgsConstructor
public class MarketplaceTransferController {

    private final StockTransferService transfers;

    @GetMapping("/mkt/seller/transfers")
    public ApiResponse<TransferDTOs.View> view() {
        return ApiResponse.success(transfers.view());
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
}
