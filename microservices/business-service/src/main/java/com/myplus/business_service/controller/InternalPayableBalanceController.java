package com.myplus.business_service.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.repository.VenderRepo;
import com.myplus.common.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * FP-4c — finance-service stamps each supplier's open expense bills and advance here (its PayableBalanceService outbox).
 *
 * <p>{@code /internal/**} has no gateway route, so only an in-network service reaches it (the BLK-0 pattern). The
 * organization is the CALLER's (fail closed when there is none) and every update is bounded by it, so a payload can
 * never stamp another tenant's supplier. Idempotent: an older version is ignored by the UPDATE itself.
 */
@RestController
@RequiredArgsConstructor
public class InternalPayableBalanceController {

    /** One supplier's figures as finance sends them. */
    public record Balance(String partyType, Long partyId, BigDecimal otherOpen, BigDecimal advance, Long version) {}

    private final VenderRepo venders;

    @PostMapping("/internal/business/payable-balances")
    public Map<String, Object> stamp(@RequestBody List<Balance> balances) {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        int stamped = 0;
        for (Balance b : balances == null ? List.<Balance>of() : balances) {
            if (b == null || b.partyId() == null || b.version() == null) continue;
            if (b.partyType() != null && !"VENDOR".equalsIgnoreCase(b.partyType())) continue;   // suppliers only
            stamped += venders.stampPayable(b.partyId(), org, nz(b.otherOpen()).max(BigDecimal.ZERO),
                    nz(b.advance()).max(BigDecimal.ZERO), b.version());
        }
        return Map.of("stamped", stamped);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
