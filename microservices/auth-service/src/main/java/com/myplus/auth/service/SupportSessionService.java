package com.myplus.auth.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.auth.entity.SupportSession;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.SupportSessionRepository;
import com.myplus.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * E5 — opening, closing and reading support sessions.
 *
 * <h3>What this replaces</h3>
 * {@code CurrentUser.organizationIdFor} used to ask <i>"are you a platform operator?"</i>, and a yes handed
 * over any tenant for any reason for ever. It now asks whether an OPEN SESSION exists for the tenant being
 * named, and this class is what creates one.
 *
 * <h3>Three rules enforced here rather than on the screen</h3>
 * <ol>
 *   <li><b>A reason is required.</b> The endpoint is reachable without the console, and the callers that skip
 *       the form are the ones nobody remembers writing.</li>
 *   <li><b>An operator cannot open a session over their own organization.</b> Not because it is dangerous —
 *       because it is meaningless, and a session that grants what the caller already has would make the
 *       audit trail claim support accessed a customer when it did not.</li>
 *   <li><b>Writes are off until the customer allows them.</b> Reading a shop's figures to answer their
 *       question and changing their records are different asks; only the second is irreversible to them.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SupportSessionService {

    /**
     * Bounds on how long a session may last.
     *
     * <p>The minimum is one minute because that is what makes expiry testable end to end without a
     * server-wide setting a spec would have to set and restore. The maximum is four hours: long enough for a
     * real investigation, short enough that forgetting to close one is not the same as never having bounded
     * it. The default is deliberately well under both — most support is minutes, and an operator who needs
     * longer can say so, which is itself worth recording.
     */
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 240;
    private static final int DEFAULT_MINUTES = 30;

    private final SupportSessionRepository sessions;
    private final UserRepository users;
    private final ControlPlaneAuditService audit;

    /**
     * Open a session over one tenant.
     *
     * @param actorOrgId the operator's own organization — passed in so self-support can be refused, and read
     *                   from the validated token by the controller rather than from the request body
     */
    @Transactional
    public SupportSession open(Long subjectOrgId, String reason, Integer minutes,
                               Long actorUserId, Long actorOrgId) {
        if (subjectOrgId == null) throw new IllegalArgumentException("organizationId is required");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A reason is required to open a support session.");
        if (actorOrgId != null && actorOrgId.equals(subjectOrgId))
            throw new IllegalArgumentException("You already have access to your own organization.");

        int mins = minutes == null ? DEFAULT_MINUTES : minutes;
        if (mins < MIN_MINUTES || mins > MAX_MINUTES)
            throw new IllegalArgumentException(
                    "A support session must be between " + MIN_MINUTES + " and " + MAX_MINUTES + " minutes.");

        String email = users.findById(actorUserId).map(User::getEmail).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        SupportSession s = sessions.save(SupportSession.builder()
                .operatorUserId(actorUserId)
                .operatorEmail(email)
                .subjectOrgId(subjectOrgId)
                .reason(reason.trim())
                .writeApproved(false)
                .openedAt(now)
                .expiresAt(now.plusMinutes(mins))
                .build());

        /*
         * Recorded against the SUBJECT, in this transaction — so a refused open leaves no trace, and the
         * customer's own trail is where the record lands. E4 built the actor axis for exactly this: the
         * event says an outsider did it, and names which one.
         */
        audit.operatorAction("SUPPORT_OPENED", "SUPPORT_SESSION", String.valueOf(s.getId()),
                subjectOrgId, null, "open", reason, actorUserId, mins + " minutes");
        return s;
    }

    /**
     * Close a session early — by the operator, or by the customer it is over.
     *
     * <p>Closing something already closed is not an error. The customer may press End at the moment it
     * expires, and answering that with a failure would suggest their instruction did not take effect.
     */
    @Transactional
    public SupportSession close(Long id, Long actorUserId, Long closedByOrgId) {
        SupportSession s = sessions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such support session: " + id));

        // A session may be ended by the operator holding it or by the tenant it is over — and by nobody else.
        boolean isOperator = s.getOperatorUserId().equals(actorUserId);
        boolean isSubject = closedByOrgId != null && closedByOrgId.equals(s.getSubjectOrgId());
        if (!isOperator && !isSubject)
            throw new IllegalArgumentException("This support session is not yours to close.");

        if (s.getClosedAt() == null) {
            s.setClosedAt(LocalDateTime.now());
            s.setClosedBy(actorUserId);
            sessions.save(s);
            audit.operatorAction("SUPPORT_CLOSED", "SUPPORT_SESSION", String.valueOf(s.getId()),
                    s.getSubjectOrgId(), "open", "closed",
                    isSubject ? "Ended by the business" : "Closed by the operator",
                    actorUserId, null);
        }
        return s;
    }

    /**
     * The customer allows this session to change their records (D-2).
     *
     * <p>Only the tenant the session is over may do this, which is the whole point — an approval the operator
     * could grant themselves is not consent.
     */
    @Transactional
    public SupportSession approveWrites(Long id, Long subjectOrgId, Long actorUserId) {
        SupportSession s = sessions.findByIdAndSubjectOrgId(id, subjectOrgId)
                .orElseThrow(() -> new IllegalArgumentException("No such support session: " + id));
        if (!s.isOpen()) throw new IllegalArgumentException("That support session has already ended.");

        s.setWriteApproved(true);
        s.setApprovedBy(actorUserId);
        s.setApprovedAt(LocalDateTime.now());
        sessions.save(s);

        // The actor here is the CUSTOMER, so this is a tenant action — the emitter derives MEMBER from the
        // actor's org matching the subject's, and the trail shows the business allowed it rather than the
        // platform taking it.
        audit.tenantAction("SUPPORT_WRITE_APPROVED", "SUPPORT_SESSION", String.valueOf(s.getId()),
                "read only", "changes allowed", s.getReason());
        return s;
    }

    /**
     * The operator's open sessions, for the claim.
     *
     * <p>Read on every token mint, so it is one indexed query and no more. Returns the subject org ids and the
     * soonest expiry — the claim carries both because a service has to be able to answer "is this still
     * valid?" without calling back.
     */
    @Transactional(readOnly = true)
    public List<SupportSession> openFor(Long operatorUserId) {
        if (operatorUserId == null) return List.of();
        return sessions.findOpenForOperator(operatorUserId, LocalDateTime.now());
    }

    /** One tenant's history — the customer's own Platform access card, and the operator's detail panel. */
    @Transactional(readOnly = true)
    public Map<String, Object> forOrganization(Long subjectOrgId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportSession s : sessions.findBySubject(subjectOrgId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("operatorEmail", s.getOperatorEmail());
            m.put("reason", s.getReason());
            m.put("openedAt", iso(s.getOpenedAt()));
            m.put("expiresAt", iso(s.getExpiresAt()));
            m.put("closedAt", iso(s.getClosedAt()));
            // Computed here rather than left to each screen to work out from three dates. Two screens
            // deriving "is it open" separately is two chances to disagree about a customer's own access.
            m.put("open", s.isOpen());
            m.put("writeApproved", s.isWriteApproved());
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationId", subjectOrgId);
        result.put("rows", out);
        return result;
    }

    /**
     * A timestamp the browser cannot misread.
     *
     * <h3>⚠ Why {@code LocalDateTime.toString()} is not good enough on the wire</h3>
     * It produces {@code 2026-09-04T20:52:10} — no zone, no offset — and a browser parses that as its OWN
     * local time. The services run UTC while the people using them are on +05:00, so a session with half an
     * hour left arrived at the console looking like it had expired four and a half hours ago: the countdown
     * computed a negative remainder and the bar quietly fell back to "you are not in a support session".
     * Good data, right on the wire, wrong on the screen — and no error anywhere.
     *
     * <p>Stamping the server's offset makes the value self-describing, so a customer in Karachi and an
     * operator in London read the same instant. This matters beyond cosmetics here: the countdown is what an
     * operator trusts to know how long they still have inside somebody else's books.
     */
    private static String iso(java.time.LocalDateTime t) {
        return t == null ? null
                : t.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString();
    }

    /** Whether an open session lets this operator WRITE to that tenant — consulted by catalog's guard. */
    @Transactional(readOnly = true)
    public boolean mayWrite(Long operatorUserId, Long subjectOrgId) {
        return openFor(operatorUserId).stream()
                .anyMatch(s -> s.getSubjectOrgId().equals(subjectOrgId) && s.isWriteApproved());
    }
}
