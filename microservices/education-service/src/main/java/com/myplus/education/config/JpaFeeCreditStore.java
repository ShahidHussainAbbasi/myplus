package com.myplus.education.config;

import com.myplus.common.credit.CreditStore;
import com.myplus.common.security.CurrentUser;
import com.myplus.education.entity.FeeCreditTxn;
import com.myplus.education.repository.FeeCreditTxnRepository;
import com.myplus.education.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Slice 0.2b: education's backing for the shared {@link com.myplus.common.credit.CreditService} — the fee-credit
 * ledger plus the cached balance on Student.
 *
 * Supplying this bean is the whole opt-in: CommonCreditAutoConfiguration is conditional on a CreditStore, so a
 * service without one gets no credit service at all.
 *
 * Tenant scoping lives HERE, not in the shared rules — the shared code never sees an org id, so it cannot read
 * across tenants by mistake.
 */
@Component
@RequiredArgsConstructor
public class JpaFeeCreditStore implements CreditStore {

    private final FeeCreditTxnRepository txnRepo;
    private final StudentRepository studentRepo;

    @Override
    @Transactional
    public void append(Long studentId, BigDecimal signedAmount, String reason, String ref) {
        txnRepo.save(FeeCreditTxn.builder()
                .studentId(studentId)
                .amount(signedAmount)
                .reason(reason)
                .ref(ref)
                .organizationId(CurrentUser.organizationId())
                .userId(CurrentUser.userId())
                .dated(LocalDateTime.now())
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balance(Long studentId) {
        BigDecimal b = txnRepo.balanceScoped(studentId, CurrentUser.organizationId());
        return b != null ? b : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public void cacheBalance(Long studentId, BigDecimal balance) {
        studentRepo.updateCreditBalance(studentId, balance);
    }
}
