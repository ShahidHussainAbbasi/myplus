package com.myplus.notification.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.myplus.common.notify.EmailRequest;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.notification.entity.NotificationBroadcast;
import com.myplus.notification.entity.NotificationDelivery;
import com.myplus.notification.repository.NotificationBroadcastRepository;
import com.myplus.notification.repository.NotificationDeliveryRepository;
import com.myplus.notification.service.NotificationService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Notification API (slice 33, Phase 8; delivery record added in slice 105). Other services POST here
 * instead of owning SMTP.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationBroadcastRepository broadcastRepo;

    /**
     * Send. Returns the raw boolean so {@code NotificationClient} deserializes it directly.
     *
     * <p><b>The existing contract is untouched.</b> {@code source}, {@code orgId} and {@code dedupeKey} are
     * optional params: every caller written before slice 105 still compiles, still calls, still behaves
     * identically — it simply records an unattributed broadcast. Callers adopt the parameters when they
     * want idempotency and a per-recipient trail. Expand now, migrate callers slice by slice.
     */
    @PostMapping("/email")
    public boolean email(@RequestBody EmailRequest request,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) Long orgId,
                         @RequestParam(required = false) String dedupeKey) {
        return notificationService.sendEmail(request, source, orgId, dedupeKey);
    }

    /**
     * What happened to one broadcast — "sent to 298, 2 failed, here are the 2".
     *
     * <p><b>Anti-IDOR.</b> The tenant comes from the authenticated principal and the broadcast is checked
     * against it; a broadcast id belonging to another school 404s rather than 403s, so the endpoint does
     * not confirm that the id exists. Same refusal shape PortalScopeFilter settled on.
     */
    @GetMapping("/broadcasts/{id}")
    public ResponseEntity<Map<String, Object>> broadcast(@PathVariable Long id, Authentication auth) {
        Long orgId = orgOf(auth);
        NotificationBroadcast b = broadcastRepo.findById(id).orElse(null);
        if (b == null || !sameTenant(b.getOrganizationId(), orgId)) return ResponseEntity.notFound().build();

        List<NotificationDelivery> rows = deliveryRepo.findByBroadcastIdOrderByIdAsc(id);
        return ResponseEntity.ok(Map.of(
                "broadcastId", b.getId(),
                "subject", b.getSubject() == null ? "" : b.getSubject(),
                "source", b.getSource() == null ? "" : b.getSource(),
                "totalRecipients", b.getTotalRecipients(),
                "counts", rows.stream().collect(Collectors.groupingBy(
                        d -> d.getStatus().name(), Collectors.counting())),
                "deliveries", rows.stream().map(this::view).collect(Collectors.toList())));
    }

    /**
     * What this address was sent, newest first — the question a school office actually asks.
     *
     * <p>Scoped to the caller's tenant in the QUERY, not filtered afterwards: an email address is not
     * unique across tenants (a parent may be a guardian at two schools here), so an unscoped lookup would
     * disclose another school's correspondence.
     */
    @GetMapping("/deliveries")
    public ResponseEntity<List<Map<String, Object>>> forRecipient(@RequestParam String recipient,
                                                                  @RequestParam(defaultValue = "50") int limit,
                                                                  Authentication auth) {
        Long orgId = orgOf(auth);
        if (orgId == null) return ResponseEntity.notFound().build();
        List<NotificationDelivery> rows = deliveryRepo.findForRecipient(
                recipient, orgId, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
        return ResponseEntity.ok(rows.stream().map(this::view).collect(Collectors.toList()));
    }

    private Map<String, Object> view(NotificationDelivery d) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("deliveryId", d.getId());
        m.put("recipient", d.getRecipient());
        m.put("status", d.getStatus().name());
        m.put("attempts", d.getAttempts());
        m.put("lastError", d.getLastError());
        m.put("sentAt", d.getSentAt());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private Long orgOf(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser u)) return null;
        return u.getOrganizationId();
    }

    /**
     * Rows written before a caller passed an orgId have a null tenant. They are readable only by a request
     * that also has no tenant — never folded into a real tenant's results, which would attribute another
     * school's mail to this one.
     */
    private boolean sameTenant(Long rowOrg, Long callerOrg) {
        return rowOrg == null ? callerOrg == null : rowOrg.equals(callerOrg);
    }
}
