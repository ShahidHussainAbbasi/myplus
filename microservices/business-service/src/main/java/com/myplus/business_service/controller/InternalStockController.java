package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ReservationStatus;
import com.myplus.commerce.contracts.dto.StockHoldRequest;
import com.myplus.commerce.contracts.dto.StockHoldResponse;
import com.myplus.commerce.contracts.dto.StockReservationLine;
import com.myplus.commerce.contracts.dto.StockReservationRequest;
import com.myplus.commerce.contracts.dto.StockReservationResponse;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * OMS O7 D1c — the ONE way another service sets stock aside for a confirmed order.
 *
 * <h3>Why the hold is taken here and not by the caller</h3>
 * business-service is the authority on what stock means for a trade sale. The last time a channel held
 * inventory on its own account it produced holds with no invoice behind them, and <b>O1 deleted that saga</b>.
 * That a hold now carries a deadline (O5a) makes reserving-at-confirm safe to attempt again; it does not make
 * a second stock authority a good idea. So marketplace asks, and this decides.
 *
 * <h3>Why it is not part of {@link InternalSalesController}</h3>
 * A hold is not a sale. It writes no invoice, touches no ledger, and needs none of that class's machinery —
 * nor its §12.5 controller-into-controller debt. Same reasoning that gave {@code /internal/receipts} its own
 * class in D5.
 *
 * <h3>An ORDER hold, and that distinction is the whole slice</h3>
 * The reserve goes out with {@link StockReservationRequest.HoldKind#ORDER}, whose deadline is measured in days
 * rather than the till's thirty minutes. Under the checkout TTL a hold taken this afternoon is swept before
 * tomorrow's van — silently, working exactly as designed — and the feature would look implemented while doing
 * nothing on any order that waited.
 *
 * <h3>Trust boundary and anti-IDOR</h3>
 * Reachable only inside the private network ({@code /internal/**} is not routed by the gateway), and the
 * caller arrives with a forwarded identity. The org in the body is checked against the org it authenticated
 * as: a hold is a claim on somebody's stock, and a caller may only claim its own tenant's — otherwise a
 * compromised in-network caller could sterilise another tenant's inventory without writing a single row.
 */
@RestController
@RequestMapping("/internal/stock")
public class InternalStockController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalStockController.class);

    @Autowired
    private InventoryClient inventoryClient;

    @Autowired
    private RequestUtil requestUtil;

    /**
     * Set this order's stock aside.
     *
     * <p>Idempotent on {@code holdKey}: inventory returns the existing hold for a repeated key rather than
     * double-holding, so a re-confirm or a retry after a timeout costs nothing.
     *
     * <p><b>A refusal is reported, not thrown.</b> Out of stock is an answer the admin is entitled to act on —
     * a distributor with a delivery due tomorrow may knowingly confirm against it. What must never happen is
     * the reverse: claiming a hold that was not taken, which would let the shop believe goods are safe while
     * another order sells them.
     */
    @PostMapping("/hold")
    public ResponseEntity<StockHoldResponse> hold(@RequestBody StockHoldRequest request) {
        if (request == null || request.getHoldKey() == null || request.getHoldKey().isBlank())
            throw new ValidationException("A holdKey is required — it is what makes a repeated confirm safe");
        if (request.getLines() == null || request.getLines().isEmpty())
            throw new ValidationException("A hold needs at least one line");

        Long org = authenticatedOrg();
        if (request.getOrganizationId() != null && !request.getOrganizationId().equals(org))
            throw new ValidationException("Organization mismatch: stock may only be held for its own tenant");

        List<StockReservationLine> lines = new ArrayList<>();
        for (StockReservationLine l : request.getLines()) {
            if (l == null || l.getItemId() == null || l.getQuantity() == null) continue;
            if (l.getQuantity().signum() <= 0) continue;   // nothing outstanding on this line
            lines.add(l);
        }
        if (lines.isEmpty())
            // Everything is already dispatched. Not an error, and not a hold either.
            return ResponseEntity.ok(StockHoldResponse.builder().held(false)
                    .reason("Nothing outstanding to hold").build());

        StockReservationResponse r = inventoryClient.reserve(new StockReservationRequest(
                request.getHoldKey(), lines, StockReservationRequest.HoldKind.ORDER));

        boolean held = r != null && r.getStatus() == ReservationStatus.RESERVED;
        if (!held) {
            LOG.info("D1c: could not hold stock for {} — {}", request.getHoldKey(),
                    r == null ? "no answer from inventory" : r.getMessage());
        }
        return ResponseEntity.ok(StockHoldResponse.builder()
                .held(held)
                .reason(held ? null : (r == null ? "Inventory did not answer" : r.getMessage()))
                .expiresAt(r == null ? null : r.getExpiresAt())
                .reservationId(r == null ? null : r.getReservationId())
                .build());
    }

    /**
     * Give a held order's stock back.
     *
     * <p>Called whenever the promise ends — rejected, cancelled, or dispatched (where the sale takes its own
     * hold, so this one must go or the goods are held twice).
     *
     * <p>Answers 200 even when there was nothing to release. The hold may already have lapsed to the expiry
     * sweeper, which is what the sweeper is for, and a caller compensating a failure should not have to
     * distinguish "already gone" from "never existed" — both mean the stock is free.
     */
    @PostMapping("/hold/release")
    public ResponseEntity<Void> release(@RequestParam("holdKey") String holdKey) {
        if (holdKey == null || holdKey.isBlank())
            throw new ValidationException("A holdKey is required");
        authenticatedOrg();   // reject an unidentified caller before touching anything

        try {
            inventoryClient.releaseByKey(holdKey);
        } catch (RuntimeException e) {
            // Best effort by design. The sweeper is the backstop, and a release that throws must not fail the
            // rejection or cancellation the admin has already made.
            LOG.warn("D1c: release of hold {} failed; the expiry sweeper will collect it: {}",
                    holdKey, e.toString());
        }
        return ResponseEntity.ok().build();
    }

    private Long authenticatedOrg() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");
        return org;
    }
}
