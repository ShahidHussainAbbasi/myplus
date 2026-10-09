package com.myplus.clinical.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.clinical.entity.ClinicProvider;
import com.myplus.clinical.entity.QueueToken;
import com.myplus.clinical.repository.ClinicProviderRepo;
import com.myplus.clinical.repository.QueueTokenRepo;
import com.myplus.common.docnum.DocumentNumberService;

import lombok.RequiredArgsConstructor;

/**
 * HMS S2 — the queue's transactional writes, in their own bean so each passes through the transaction proxy.
 *
 * <p>{@link #insert} takes the token number LAST and makes no remote call: the counter row is locked until commit
 * and every other front desk waits behind it ("allocate late", common-docnum). A token that fails on
 * uq_token_live rolls back with its number, so numbers are not burned.
 */
@Service
@RequiredArgsConstructor
public class TokenWriter {

    private final QueueTokenRepo tokens;
    private final ClinicProviderRepo providers;
    private final DocumentNumberService numbers;

    @Transactional
    public QueueToken insert(QueueToken t, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        int no = (int) numbers.next(t.getOrganizationId(), QueueRules.counterKey(t.getProviderId(), t.getVisitDate()));
        t.setTokenNo(no);
        t.setTokenLabel(QueueRules.label(prefix, no));
        return tokens.saveAndFlush(t);
    }

    @Transactional
    public int transition(Long id, Long org, Collection<String> from, String to, Long user, String reason) {
        return tokens.transition(id, org, from, to, LocalDateTime.now(), user, reason);
    }

    /**
     * The doctor's token letter, assigned on first use. Its own transaction: two desks giving the clinic's new
     * doctor a first token at the same moment race on uq_provider_prefix; the loser simply reads the winner's row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String assignPrefix(Long org, Long providerId) {
        Set<String> taken = providers.findByOrganizationId(org).stream()
                .map(ClinicProvider::getTokenPrefix).collect(Collectors.toSet());
        ClinicProvider cp = new ClinicProvider();
        cp.setOrganizationId(org);
        cp.setProviderId(providerId);
        cp.setTokenPrefix(QueueRules.nextPrefix(taken));
        cp.setCreatedAt(LocalDateTime.now());
        providers.saveAndFlush(cp);
        return cp.getTokenPrefix();
    }
}
