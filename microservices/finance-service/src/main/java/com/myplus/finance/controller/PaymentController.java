package com.myplus.finance.controller;

import com.myplus.finance.dto.PaymentDTO;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.service.PaymentService;
import jakarta.validation.Valid;
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

    /** Record a payment (+ allocations) in the ledger. */
    @PostMapping("/payments")
    public PaymentDTO record(@Valid @RequestBody RecordPaymentRequest req) {
        return paymentService.record(req);
    }

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
