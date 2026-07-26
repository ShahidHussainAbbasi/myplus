package com.myplus.party.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.party.dto.PartyContactViewDTO;
import com.myplus.party.dto.PartyDTO;
import com.myplus.party.dto.PartyRoleDTO;
import com.myplus.party.entity.Party;
import com.myplus.party.repository.PartyRepository;
import com.myplus.party.repository.PartyRoleLinkRepository;

import lombok.RequiredArgsConstructor;

/**
 * The contact master. Owns common identity + issues a stable partyId. {@link #upsert} is the bridge entry point every
 * module calls on write: it find-or-creates a party by de-dup key (contact within the org, else email) so the same
 * person entered in POS, pharmacy and education resolves to ONE partyId. Tenant-scoped (org + NULL-fallback), never
 * trusts client identity (org/user come from {@link CurrentUser}). No domain data here.
 */
@Service
@RequiredArgsConstructor
public class PartyService {

    private final PartyRepository repo;
    private final PartyRoleLinkRepository linkRepo;

    @Transactional(readOnly = true)
    public List<PartyDTO> list() {
        return repo.findScoped(CurrentUser.organizationId(), CurrentUser.userId()).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<PartyDTO> search(String q) {
        if (q == null || q.isBlank()) return list();
        return repo.searchScoped(q.trim(), CurrentUser.organizationId(), CurrentUser.userId()).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PartyDTO get(Long id) {
        return repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId()).map(this::toDto).orElse(null);
    }

    @Transactional
    public PartyDTO create(PartyDTO dto) {
        return toDto(createEntity(dto));
    }

    private Party createEntity(PartyDTO dto) {
        Party p = new Party();
        p.setOrganizationId(CurrentUser.organizationId());
        p.setUserId(CurrentUser.userId());
        apply(p, dto);
        return repo.save(p);
    }

    @Transactional
    public PartyDTO update(Long id, PartyDTO dto) {
        Party p = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId()).orElse(null);
        if (p == null) return null;
        apply(p, dto);
        return toDto(repo.save(p));
    }

    /**
     * The bridge entry point: find-or-create a party by de-dup key and return it. Match order: (org, contact) first,
     * then (org, email). On a match, fill any blank identity fields (a later module often has more detail) and keep
     * the earliest partyId. This is what makes one person shared across modules.
     */
    @Transactional
    public PartyDTO upsert(PartyDTO dto) {
        Long org = CurrentUser.organizationId();
        Party match = null;
        if (dto.getContact() != null && !dto.getContact().isBlank())
            match = repo.findByOrgAndContact(org, dto.getContact().trim()).orElse(null);
        if (match == null && dto.getEmail() != null && !dto.getEmail().isBlank())
            match = repo.findByOrgAndEmail(org, dto.getEmail().trim()).stream().findFirst().orElse(null);

        Party saved;
        if (match != null) {
            fillBlanks(match, dto);
            saved = repo.save(match);
        } else {
            saved = createEntity(dto);
        }
        recordLink(saved, dto.getRole());   // P4: identity AND role in one call — no extra round trip for the bridge
        return toDto(saved);
    }

    // ---- P4 role index / contact view ------------------------------------------------------------------------------

    /**
     * The cross-module contact view: the shared identity + every module role it plays. ONE indexed query, no fan-out.
     * Returns null when the party doesn't exist OR belongs to another tenant — the caller answers 404 either way, so a
     * foreign party is indistinguishable from a missing one (no cross-tenant existence probe).
     */
    @Transactional(readOnly = true)
    public PartyContactViewDTO contactView(Long id) {
        Party p = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId()).orElse(null);
        if (p == null) return null;
        List<PartyRoleDTO> roles = linkRepo.findForParty(p.getId(), CurrentUser.organizationId()).stream()
                .map(l -> PartyRoleDTO.builder()
                        .module(l.getModule()).role(l.getRole()).localId(l.getLocalId()).label(l.getLabel()).build())
                .toList();
        return PartyContactViewDTO.builder().party(toDto(p)).roles(roles).build();
    }

    /**
     * Record a role link for an ALREADY-known partyId — the backfill path (records bridged before P4 carry a party_id
     * but no link, and the skip-guard means they never bridge again). Returns false if the party isn't visible to this
     * tenant. Idempotent.
     */
    @Transactional
    public boolean link(Long partyId, PartyRoleDTO role) {
        Party p = repo.findByIdScoped(partyId, CurrentUser.organizationId(), CurrentUser.userId()).orElse(null);
        if (p == null) return false;
        recordLink(p, role);
        return true;
    }

    /**
     * Bulk link — the backfill path. ONE call per batch instead of one per row, and ONE scoped query to decide which
     * parties this tenant may touch (rather than a lookup per row). Foreign/unknown party ids are silently skipped:
     * a backfill is a maintenance sweep, not a probe. Returns how many links were written.
     */
    @Transactional
    public int linkBulk(List<PartyRoleDTO> items) {
        if (items == null || items.isEmpty()) return 0;
        List<Long> ids = items.stream().map(PartyRoleDTO::getPartyId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return 0;

        Map<Long, Party> allowed = repo.findAllByIdScoped(ids, CurrentUser.organizationId(), CurrentUser.userId())
                .stream().collect(Collectors.toMap(Party::getId, p -> p));
        int linked = 0;
        for (PartyRoleDTO item : items) {
            Party p = item.getPartyId() == null ? null : allowed.get(item.getPartyId());
            if (p == null) continue;
            recordLink(p, item);
            linked++;
        }
        return linked;
    }

    /** Idempotent (ON DUPLICATE KEY) — safe on every retry. A blank/absent role is simply not a link. */
    private void recordLink(Party p, PartyRoleDTO role) {
        if (role == null || p == null || p.getId() == null) return;
        if (isBlank(role.getModule()) || isBlank(role.getRole()) || role.getLocalId() == null) return;
        linkRepo.upsertLink(p.getOrganizationId(), p.getId(),
                role.getModule().trim().toLowerCase(), role.getRole().trim().toUpperCase(),
                role.getLocalId(), truncate(role.getLabel(), 160));
    }

    private static String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max);
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    private void apply(Party p, PartyDTO dto) {
        if (dto.getName() != null && !dto.getName().isBlank()) p.setName(dto.getName().trim());
        if (dto.getPartyType() != null) p.setPartyType(dto.getPartyType().trim().toUpperCase());
        p.setContact(blankToNull(dto.getContact()));
        p.setEmail(blankToNull(dto.getEmail()));
        p.setAddress(dto.getAddress());
        p.setNotes(dto.getNotes());
        if (dto.getActive() != null) p.setActive(dto.getActive());
    }

    /** On an upsert match, only enrich empty fields — never overwrite existing identity with a blank. */
    private void fillBlanks(Party p, PartyDTO dto) {
        if (isBlank(p.getName()) && !isBlank(dto.getName())) p.setName(dto.getName().trim());
        if (isBlank(p.getEmail()) && !isBlank(dto.getEmail())) p.setEmail(dto.getEmail().trim());
        if (isBlank(p.getContact()) && !isBlank(dto.getContact())) p.setContact(dto.getContact().trim());
        if (isBlank(p.getAddress()) && !isBlank(dto.getAddress())) p.setAddress(dto.getAddress());
        if (isBlank(p.getPartyType()) && !isBlank(dto.getPartyType())) p.setPartyType(dto.getPartyType().trim().toUpperCase());
    }

    private PartyDTO toDto(Party p) {
        return PartyDTO.builder()
                .id(p.getId()).partyType(p.getPartyType()).name(p.getName())
                .contact(p.getContact()).email(p.getEmail()).address(p.getAddress())
                .notes(p.getNotes()).active(p.getActive())
                .build();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
}
