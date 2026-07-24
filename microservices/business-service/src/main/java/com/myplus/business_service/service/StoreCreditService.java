package com.myplus.business_service.service;

import com.myplus.business_service.entity.StoreCreditTxn;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.StoreCreditRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Store credit (SF-5 Model B). Issues/redeems store credit against the ledger and keeps the customer's cached
 * {@code creditBalance} in sync (= Σ ledger, tenant-scoped). {@code redeem} never lets the balance go negative
 * (over-redemption is rejected). Every mutation is one row so a void can reverse it (issue↔redeem).
 */
@Service
@RequiredArgsConstructor
public class StoreCreditService {

    private final StoreCreditRepo repo;
    private final CustomerRepo customerRepo;
    private final RequestUtil requestUtil;

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static BigDecimal scale(BigDecimal v) { return nz(v).setScale(2, RoundingMode.HALF_UP); }

    /** The customer's current store-credit balance (tenant-scoped). */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long customerId) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        Long org = u != null ? u.getOrganizationId() : null, user = u != null ? u.getUserId() : null;
        return scale(repo.balanceScoped(customerId, org, user));
    }

    /** Issue store credit (+balance). Returns the amount issued (0 if amount ≤ 0). */
    @Transactional
    public BigDecimal issue(Long customerId, BigDecimal amount, String reason, String ref) {
        BigDecimal amt = scale(amount);
        if (customerId == null || amt.signum() <= 0) return BigDecimal.ZERO;
        write(customerId, amt, reason != null ? reason : "RETURN", ref);
        return amt;
    }

    /** Redeem store credit (−balance), capped at the current balance. Returns the amount actually redeemed. */
    @Transactional
    public BigDecimal redeem(Long customerId, BigDecimal amount, String ref) {
        BigDecimal want = scale(amount);
        if (customerId == null || want.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal bal = balance(customerId);
        BigDecimal take = want.min(bal);                     // never overdraw
        if (take.signum() <= 0) return BigDecimal.ZERO;
        write(customerId, take.negate(), "REDEEM", ref);
        return take;
    }

    private void write(Long customerId, BigDecimal signedAmount, String reason, String ref) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        repo.save(StoreCreditTxn.builder()
                .customerId(customerId).amount(scale(signedAmount)).reason(reason).ref(ref)
                .organizationId(u != null ? u.getOrganizationId() : null)
                .userId(u != null ? u.getUserId() : null)
                .storeId(requestUtil.activeStoreId())
                .dated(LocalDateTime.now()).build());
        recomputeCredit(customerId);
    }

    /** Re-sum the ledger into the customer's cached credit_balance (targeted update — no full-entity save). */
    @Transactional
    public void recomputeCredit(Long customerId) {
        customerRepo.updateCreditBalance(customerId, balance(customerId));
    }
}
