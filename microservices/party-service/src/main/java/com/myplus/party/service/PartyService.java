package com.myplus.party.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.party.dto.PartyDTO;
import com.myplus.party.entity.Party;
import com.myplus.party.repository.PartyRepository;

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
        Party p = new Party();
        p.setOrganizationId(CurrentUser.organizationId());
        p.setUserId(CurrentUser.userId());
        apply(p, dto);
        return toDto(repo.save(p));
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

        if (match != null) {
            fillBlanks(match, dto);
            return toDto(repo.save(match));
        }
        return create(dto);
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
