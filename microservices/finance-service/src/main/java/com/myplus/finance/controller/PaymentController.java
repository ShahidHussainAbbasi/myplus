package com.myplus.finance.controller;

import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * The shared payment-ledger API. Raw bodies so inter-service clients (business-service FinanceClient, and later
 * education/pharma/ecommerce) deserialize them directly. All reads/writes are tenant-scoped in the service.
 */
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /*
     * ⚠ THE WRITE HAS MOVED to InternalPaymentController at /internal/finance/payments (BLK-0).
     *
     * It lived here, and /api/finance/** IS gateway-routed — so any holder of a valid JWT could write rows
     * straight into the ledger, bypassing AR/AP allocation and the idempotency that protects the screens,
     * with no audit record of who did it. No gateway route matches /internal/**, which is what closes it.
     *
     * The READS below stay public on purpose: they are tenant-scoped and FinanceReportService calls them
     * for statements. A read was never the exposure.
     *
     * Do not re-add a write here.
     */

    /** A party's payment history (newest first), tenant-scoped. */
    @GetMapping("/payments")
    public List<PaymentDTO> byParty(@RequestParam PartyType partyType, @RequestParam Long partyId) {
        return paymentService.listByParty(partyType, partyId);
    }

    /** Total received/paid for a party, tenant-scoped. */
    @GetMapping("/payments/summary")
    public BigDecimal summary(@RequestParam PartyType partyType, @RequestParam Long partyId) {
        return paymentService.totalByParty(partyType, partyId);
    }
}
