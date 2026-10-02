package com.myplus.finance.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.finance.dto.PayableSnapshot;
import com.myplus.finance.service.PayableService;

import lombok.RequiredArgsConstructor;

/**
 * FP-1 — the payables subledger.
 *
 * <p>The WRITE is under {@code /internal/finance} — no gateway route matches it, so only an in-network service
 * reaches it (the BLK-0 pattern; see {@link InternalPaymentController}). The READS are tenant-scoped under
 * {@code /api/finance/payables}: summary (what FP-4 will serve to supplier screens) and the reconciliation control.
 */
@RestController
@RequiredArgsConstructor
public class PayableController {

    private final PayableService payables;

    @PostMapping("/internal/finance/payables")
    public Map<String, Object> upsert(@RequestBody List<PayableSnapshot> snapshots) {
        return Map.of("applied", payables.upsert(snapshots));
    }

    @GetMapping("/api/finance/payables/summary")
    public Map<String, Object> summary() {
        return payables.summary();
    }

    @GetMapping("/api/finance/payables/reconciliation")
    public Map<String, Object> reconciliation() {
        return payables.reconciliation();
    }
}
