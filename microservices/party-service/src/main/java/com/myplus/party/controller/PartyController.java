package com.myplus.party.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.myplus.party.dto.PartyContactViewDTO;
import com.myplus.party.dto.PartyDTO;
import com.myplus.party.dto.PartyRoleDTO;
import com.myplus.party.service.PartyService;

import lombok.RequiredArgsConstructor;

/**
 * The party/contact master API. Mapped at the full {@code /api/party/...} path (gateway routes {@code /api/party/**}
 * here, no StripPrefix). CRUD for the contact screen; {@code upsert} is the bridge modules call on write (find-or-
 * create by de-dup key); {@code lookup} matches an existing party. Org-scoped inside the service.
 */
@RestController
@RequestMapping("/api/party/parties")
@RequiredArgsConstructor
public class PartyController {

    private final PartyService service;

    @GetMapping
    public List<PartyDTO> list(@RequestParam(required = false) String q) {
        return service.search(q);
    }

    @GetMapping("/{id}")
    public PartyDTO get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public PartyDTO create(@RequestBody PartyDTO dto) {
        return service.create(dto);
    }

    @PutMapping("/{id}")
    public PartyDTO update(@PathVariable Long id, @RequestBody PartyDTO dto) {
        return service.update(id, dto);
    }

    /** Bridge entry point: find-or-create a party by de-dup key (contact, then email) and return it (with its id).
     *  An optional {@code role} on the body also records the caller's role link — identity + role in ONE call. */
    @PostMapping("/upsert")
    public PartyDTO upsert(@RequestBody PartyDTO dto) {
        return service.upsert(dto);
    }

    /**
     * The cross-module contact view: identity + every module role this party plays.
     * <p>ADMIN/owner-gated on purpose: the mere EXISTENCE of a {@code pharma/PATIENT} role is sensitive — a POS cashier
     * must not learn that a customer is a patient. Role rows carry a display label and nothing more.
     * <p>404 (not 403) for a party in another tenant, so the endpoint can't be used to probe cross-tenant existence.
     */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @GetMapping("/{id}/roles")
    public ResponseEntity<PartyContactViewDTO> roles(@PathVariable Long id) {
        PartyContactViewDTO view = service.contactView(id);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    /** Record a role link for a known partyId. Idempotent; 404 for a foreign party.
     *  Owner/admin-gated like the read: a link is an assertion about someone's identity, and the only legitimate
     *  callers are the owner-triggered module backfills (which forward the triggering owner's identity). The bridge's
     *  hot path is unaffected — it records its role through {@code /upsert}, which stays open to any authenticated user. */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @PostMapping("/{id}/roles")
    public ResponseEntity<Void> link(@PathVariable Long id, @RequestBody PartyRoleDTO role) {
        return service.link(id, role) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /** Bulk link — the per-module backfill path: one call per batch (each item carries its own partyId). */
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    @PostMapping("/roles/bulk")
    public int linkBulk(@RequestBody List<PartyRoleDTO> links) {
        return service.linkBulk(links);
    }
}
