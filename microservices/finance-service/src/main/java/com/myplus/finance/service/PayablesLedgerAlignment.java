package com.myplus.finance.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.security.time.TenantClock;
import com.myplus.finance.dto.JournalLineDTO;
import com.myplus.finance.dto.JournalPostRequest;
import com.myplus.finance.entity.ProcessedEvent;
import com.myplus.finance.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;

/**
 * FP-6a — brings GL 2000 to what the supplier ledger nets to, automatically. Design:
 * microservices/docs/slices/fp-6-retire-business-source.md.
 *
 * <p>Called by business's daily payables reconciliation ONLY after business's purchases and finance's documents agree
 * (so the supplier ledger is proven right). The difference is computed HERE, at the moment of posting — never taken
 * from the caller — and posted as ONE append-only journal against 2990 "Payables Reconciliation Difference":
 * GL above the ledger → Dr 2000 / Cr 2990; below → Dr 2990 / Cr 2000. Idempotent per run key through the same
 * {@code gl_processed_event} claim every posting uses — keyed by the ledger's STATE (account 2000's newest line), so a
 * retried or concurrent run can never post twice, and a new difference later the same day is still aligned.
 */
@Service
@RequiredArgsConstructor
public class PayablesLedgerAlignment {

    private static final Logger LOG = LoggerFactory.getLogger(PayablesLedgerAlignment.class);
    static final String AP = "2000";
    static final String DIFFERENCE = "2990";

    private final PayableService payables;
    private final GlService gl;
    private final ProcessedEventRepository processedEvents;
    private final com.myplus.finance.repository.AccountRepository accounts;
    private final com.myplus.finance.repository.JournalLineRepository lines;

    @Transactional
    public Map<String, Object> align(String runKey) {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new IllegalStateException("No tenant identity on the request");
        if (runKey == null || runKey.isBlank()) throw new IllegalArgumentException("runKey is required");
        Map<String, Object> out = new LinkedHashMap<>();
        gl.ensureDefaults();
        /*
         * Idempotent per LEDGER STATE, not per day: the key is this tenant's 2000 account and its newest journal line.
         * Two runs that see the same ledger (a retry, a concurrent instance) claim the same key and only one posts; the
         * alignment itself adds a line, so a NEW difference later the same day has a new state and is aligned too.
         * (A per-day key left a second difference standing until tomorrow — found by the FP-6a gate, case 2.)
         */
        Long apAccount = accounts.findByOrganizationIdAndCode(org, AP).map(a -> a.getId()).orElse(null);
        String key = "AP-RECON:" + apAccount + ":" + (apAccount == null ? 0 : lines.maxLineIdForAccount(apAccount));
        if (processedEvents.existsByOrganizationIdAndEventKey(org, key)) {
            out.put("replay", true);
            out.put("posted", BigDecimal.ZERO);
            return out;
        }
        processedEvents.saveAndFlush(ProcessedEvent.builder().organizationId(org).eventKey(key).createdAt(LocalDateTime.now()).build());
        BigDecimal diff = (BigDecimal) payables.reconciliation().get("difference");   // GL 2000 − ledger, right now
        out.put("difference", diff);
        if (diff == null || diff.signum() == 0) {
            out.put("posted", BigDecimal.ZERO);
            return out;
        }
        gl.postJournal(JournalPostRequest.builder()
                .entryDate(TenantClock.today())
                .source("AP_RECON").sourceRef(runKey)
                .memo("Payables reconciliation " + runKey + ": GL 2000 aligned to the supplier ledger")
                .lines(lines(diff)).build());
        LOG.warn("FP-6a: org {} GL 2000 differed from the supplier ledger by {}; aligned against {} (run {})", org, diff, DIFFERENCE, runKey);
        out.put("posted", diff);
        return out;
    }

    /** The journal for a difference (GL − ledger): static so the rule is unit-tested without a ledger. */
    static List<JournalLineDTO> lines(BigDecimal diff) {
        BigDecimal amt = diff.abs();
        return diff.signum() > 0
                ? List.of(JournalLineDTO.builder().accountCode(AP).debit(amt).build(),
                          JournalLineDTO.builder().accountCode(DIFFERENCE).credit(amt).build())
                : List.of(JournalLineDTO.builder().accountCode(DIFFERENCE).debit(amt).build(),
                          JournalLineDTO.builder().accountCode(AP).credit(amt).build());
    }
}
