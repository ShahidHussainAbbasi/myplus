package com.myplus.pharma.service;

import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.common.security.CurrentUser;
import com.myplus.pharma.entity.MedicineClinical;
import com.myplus.pharma.repository.MedicineClinicalRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * One-time backfill of the pharmacy clinical flags from {@code medicine_clinical} into the catalog product master
 * (review B1, design D6).
 *
 * Why an endpoint and not a Flyway script: the two tables live in DIFFERENT databases (myplusdb_pharma vs
 * myplusdb_catalog), so no single migration can see both. Same shape as this service's party-link backfill —
 * batched by id cursor, idempotent, re-run until {@code remaining} is 0.
 *
 * THIS MATTERS AT DEPLOY TIME: the sell guard reads the flag from catalog, so any medicine flagged before the
 * cutover is UNENFORCED until this has run. It belongs in the deploy, not in a follow-up ticket.
 */
@Service
@RequiredArgsConstructor
public class ClinicalFlagBackfillService {

    private static final Logger LOG = LoggerFactory.getLogger(ClinicalFlagBackfillService.class);

    /** Bounded so a batch finishes well inside the client timeout; call again with the returned cursor. */
    private static final int MAX_BATCH = 200;

    private final MedicineClinicalRepository clinicalRepo;
    private final CatalogClient catalogClient;

    @Transactional(readOnly = true)
    public Map<String, Object> backfill(int limit, Long afterId) {
        Long orgId = CurrentUser.organizationId(), userId = CurrentUser.userId();
        long after = afterId == null ? 0L : afterId;
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, MAX_BATCH)));

        int scanned = 0, pushed = 0, failed = 0;
        for (MedicineClinical c : clinicalRepo.findAfter(after, orgId, userId, page)) {
            after = Math.max(after, c.getId());
            scanned++;
            if (c.getProductId() == null) continue;
            try {
                // Idempotent: writing the same two booleans again is a no-op at the destination.
                catalogClient.updateClinicalFlags(c.getProductId(), c.isRxRequired(), c.isControlledSubstance());
                pushed++;
            } catch (Exception e) {
                failed++;
                LOG.warn("clinical-flag backfill failed for product {} — re-run to retry", c.getProductId(), e);
            }
        }
        return Map.of("scanned", scanned, "pushed", pushed, "failed", failed, "lastId", after,
                "remaining", clinicalRepo.countAfter(after, orgId, userId));
    }
}
