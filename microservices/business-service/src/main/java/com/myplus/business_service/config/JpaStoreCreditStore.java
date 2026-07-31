package com.myplus.business_service.config;

import com.myplus.business_service.entity.StoreCreditTxn;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.StoreCreditRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.common.credit.CreditStore;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Slice 0.2b: POS's backing for the shared {@link com.myplus.common.credit.CreditService}.
 *
 * Behaviour is byte-for-byte what {@code StoreCreditService} did before — same table, same scoping, same storeId
 * stamping. Only the RULES (append-only, capped redeem, recompute the cache) moved into common-credit, so POS and
 * education can no longer drift apart on them.
 */
@Component
@RequiredArgsConstructor
public class JpaStoreCreditStore implements CreditStore {

    private final StoreCreditRepo repo;
    private final CustomerRepo customerRepo;
    private final RequestUtil requestUtil;

    @Override
    @Transactional
    public void append(Long customerId, BigDecimal signedAmount, String reason, String ref) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        repo.save(StoreCreditTxn.builder()
                .customerId(customerId).amount(signedAmount).reason(reason).ref(ref)
                .organizationId(u != null ? u.getOrganizationId() : null)
                .userId(u != null ? u.getUserId() : null)
                .storeId(requestUtil.activeStoreId())   // POS-only: which store issued/redeemed it
                .dated(LocalDateTime.now()).build());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balance(Long customerId) {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        BigDecimal b = repo.balanceScoped(customerId,
                u != null ? u.getOrganizationId() : null, u != null ? u.getUserId() : null);
        return b != null ? b : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public void cacheBalance(Long customerId, BigDecimal balance) {
        customerRepo.updateCreditBalance(customerId, balance);
    }
}
