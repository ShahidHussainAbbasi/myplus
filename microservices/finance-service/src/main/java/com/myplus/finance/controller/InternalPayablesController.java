package com.myplus.finance.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.finance.service.PayablesLedgerAlignment;

import lombok.RequiredArgsConstructor;

/**
 * FP-6a — align GL 2000 to the supplier ledger for the caller's tenant. Business calls it from its automatic daily
 * reconciliation, only once business's purchases and finance's documents agree; idempotent per ledger state. Like every
 * {@code /internal/**} write, no gateway route reaches it (see InternalPaymentController).
 */
@RestController
@RequestMapping("/internal/finance")
@RequiredArgsConstructor
public class InternalPayablesController {

    private final PayablesLedgerAlignment alignment;

    @PostMapping("/payables/align-ledger")
    public Map<String, Object> alignLedger(@RequestParam("runKey") String runKey) {
        return alignment.align(runKey);
    }
}
