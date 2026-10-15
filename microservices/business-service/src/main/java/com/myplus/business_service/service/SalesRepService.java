package com.myplus.business_service.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.SalesRep;
import com.myplus.business_service.repository.SalesRepRepository;

/**
 * SRA-2 — a business's sales reps (V83). Design: {@code microservices/docs/slices/sra-sales-by-rep-and-area.md} §5.
 *
 * <p>Refusals are {@link IllegalArgumentException}s with a sentence an owner can act on; the controller answers them
 * as FAILED. Every id from a request is resolved inside the caller's business.
 */
@Service
public class SalesRepService {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    static final int MAX_NAME = 120;

    private final SalesRepRepository reps;

    public SalesRepService(SalesRepRepository reps) {
        this.reps = reps;
    }

    @Transactional(readOnly = true)
    public List<SalesRep> list(Long orgId) {
        return reps.findByOrg(orgId);
    }

    /** The rep this login IS in this business, if any — active or not (a deactivated rep is still not "everyone"). */
    @Transactional(readOnly = true)
    public Optional<SalesRep> repForUser(Long orgId, Long userId) {
        if (orgId == null || userId == null) return Optional.empty();
        return reps.findByUser(orgId, userId);
    }

    /** A rep of THIS business, or empty — the anti-IDOR check every writer of a rep id goes through. */
    @Transactional(readOnly = true)
    public Optional<SalesRep> inOrg(Long repId, Long orgId) {
        if (repId == null || orgId == null) return Optional.empty();
        return reps.findByIdScoped(repId, orgId);
    }

    @Transactional
    public SalesRep create(Long orgId, Long actorId, String rawName, String phone, Long userId) {
        String name = cleanName(rawName);
        reps.findByName(orgId, name).ifPresent(r -> { throw new IllegalArgumentException("A rep named " + r.getName() + " already exists."); });
        if (userId != null) refuseLinkedElsewhere(orgId, userId, null);
        return reps.saveAndFlush(SalesRep.builder().organizationId(orgId).name(name).phone(blankToNull(phone))
                .userId(userId).createdBy(actorId).build());
    }

    /**
     * Edit a rep. {@code userId} 0 = UNLINK the login (null = leave it). {@code status} ACTIVE / INACTIVE, or null to
     * leave it. The rep's history is untouched either way: sales and outlets point at the rep, not at the login.
     */
    @Transactional
    public SalesRep update(Long orgId, Long repId, String rawName, String phone, Long userId, String status) {
        SalesRep r = inOrg(repId, orgId).orElseThrow(() -> new IllegalArgumentException("Rep not found."));
        if (rawName != null && !rawName.isBlank()) {
            String name = cleanName(rawName);
            reps.findByName(orgId, name).filter(o -> !o.getId().equals(r.getId()))
                    .ifPresent(o -> { throw new IllegalArgumentException("A rep named " + o.getName() + " already exists."); });
            r.setName(name);
        }
        if (phone != null) r.setPhone(blankToNull(phone));
        if (userId != null) {
            if (userId == 0L) {
                r.setUserId(null);
            } else {
                refuseLinkedElsewhere(orgId, userId, r.getId());
                r.setUserId(userId);
            }
        }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            if (!ACTIVE.equals(s) && !INACTIVE.equals(s)) throw new IllegalArgumentException("Status must be ACTIVE or INACTIVE.");
            r.setStatus(s);
        }
        return reps.saveAndFlush(r);
    }

    /** One login is at most one rep, or their bookings would split across two names (V83's UNIQUE key says it too). */
    private void refuseLinkedElsewhere(Long orgId, Long userId, Long exceptRepId) {
        reps.findByUser(orgId, userId).filter(o -> exceptRepId == null || !o.getId().equals(exceptRepId))
                .ifPresent(o -> { throw new IllegalArgumentException("This login is already linked to " + o.getName() + "."); });
    }

    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("Rep name is required.");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("Rep name is too long (" + MAX_NAME + " characters at most).");
        return name;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
