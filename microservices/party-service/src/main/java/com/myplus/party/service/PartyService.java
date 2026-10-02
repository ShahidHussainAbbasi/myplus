package com.myplus.party.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.CurrentUser;
import com.myplus.party.dto.PartyContactViewDTO;
import com.myplus.party.dto.PartyDTO;
import com.myplus.party.dto.PartyDuplicateGroupDTO;
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
        return createEntity(dto, null);
    }

    /** {@code heldBackPhone}: a phone whose raw text another partner already holds — kept as this partner's KEY only. */
    private Party createEntity(PartyDTO dto, String heldBackPhone) {
        Party p = new Party();
        p.setOrganizationId(CurrentUser.organizationId());
        p.setUserId(CurrentUser.userId());
        apply(p, dto);
        p.setPhoneWithoutContact(heldBackPhone);
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
        // DR-1: tax key -> phone key -> (raw contact) -> email, and email never against a stronger key. See PartyMatcher.
        Party match = PartyMatcher.match(dto.getTaxId(), dto.getContact(), dto.getEmail(), new PartyMatcher.Lookup() {
            public List<Party> byTaxKey(String k) { return repo.findByOrgAndTaxKey(org, k); }
            public List<Party> byContactKey(String k) { return repo.findByOrgAndContactKey(org, k); }
            public java.util.Optional<Party> byRawContact(String c) { return repo.findByOrgAndContact(org, c); }
            public List<Party> byEmail(String e) { return repo.findByOrgAndEmail(org, e); }
        });

        Party saved;
        if (match != null) {
            fillBlanks(match, dto);
            saved = repo.save(match);
        } else {
            // A different partner may legitimately share this exact phone text (same number, different CNIC). The raw
            // UNIQUE (org, contact) predates DR-1 and stays, so the newcomer is created without the raw text; it keeps
            // its tax key, which is what identifies it.
            String heldBack = null;
            if (dto.getContact() != null && !dto.getContact().isBlank()
                    && repo.findByOrgAndContact(org, dto.getContact().trim()).isPresent()) {
                heldBack = dto.getContact().trim();
                dto.setContact(null);
            }
            saved = createEntity(dto, heldBack);
        }
        recordLink(saved, dto.getRole());   // P4: identity AND role in one call — no extra round trip for the bridge
        return toDto(saved);
    }

    /**
     * DR-2 — UNLINK: give one module record a partner of its own, deliberately WITHOUT matching (matching is what put it
     * with the partner it is leaving). The role link moves to the new partner in the same transaction.
     * The raw phone text is held back when another partner holds it, exactly as {@link #upsert} does — the new partner
     * keeps the phone KEY, so it still shows under "possible duplicates".
     */
    @Transactional
    public PartyDTO detach(PartyDTO dto) {
        if (dto == null || dto.getRole() == null || dto.getRole().getLocalId() == null) return null;
        Long org = CurrentUser.organizationId();
        String heldBack = null;
        if (dto.getContact() != null && !dto.getContact().isBlank()
                && repo.findByOrgAndContact(org, dto.getContact().trim()).isPresent()) {
            heldBack = dto.getContact().trim();
            dto.setContact(null);
        }
        Party created = createEntity(dto, heldBack);
        recordLink(created, dto.getRole());
        return toDto(created);
    }

    // ---- Phase 4a account hierarchy ---------------------------------------------------------------------------------

    /** Depth cap: COMPANY → BRANCH → CONTACT. Deeper is a modelling error, not a feature. */
    private static final int MAX_DEPTH = 3;

    public static final String COMPANY = "COMPANY";
    public static final String BRANCH = "BRANCH";
    public static final String CONTACT = "CONTACT";
    public static final String INDIVIDUAL = "INDIVIDUAL";

    /**
     * Place a party in the account hierarchy — the single write path for {@code parentPartyId}/{@code accountLevel}.
     *
     * <p>Every invariant is enforced HERE, on write, rather than defended at read time, because a cycle or a
     * cross-tenant parent that reaches the table is already a corrupted tree: the next reader either loops forever
     * or sees another tenant's company. Re-parenting an existing node is the operation that actually introduces
     * cycles, so this runs on edit exactly as on create.
     *
     * @param parentId null detaches the party (makes it a root)
     * @throws IllegalArgumentException with a message meant for the operator
     */
    @Transactional
    public PartyDTO setAccountParent(Long id, Long parentId, String accountLevel) {
        Long org = CurrentUser.organizationId(), user = CurrentUser.userId();
        Party child = repo.findByIdScoped(id, org, user)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));

        String level = normaliseLevel(accountLevel, child.getAccountLevel());

        if (parentId == null) {
            // Detaching is always safe — it can neither create a cycle nor cross a tenant.
            child.setParentPartyId(null);
            child.setAccountLevel(INDIVIDUAL.equals(level) ? INDIVIDUAL : COMPANY);
            return toDto(repo.save(child));
        }

        if (parentId.equals(id)) throw new IllegalArgumentException("An account cannot be its own parent.");
        if (INDIVIDUAL.equals(level))
            throw new IllegalArgumentException("An individual account cannot sit under a company — set its level to BRANCH or CONTACT first.");

        // Anti-IDOR: a scoped read, so a parent in another tenant is indistinguishable from one that doesn't exist.
        Party parent = repo.findByIdScoped(parentId, org, user)
                .orElseThrow(() -> new IllegalArgumentException("Parent account not found: " + parentId));

        assertNoCycle(child.getId(), parent, org, user);
        assertDepthWithinCap(parent, org, user);

        // Attaching the first child IS the act of making this row a group head, so promote rather than refuse.
        // Refusing forced a hidden two-step — promote the parent, then attach — that the UI gives no way to
        // perform: its level dropdown sets the CHILD's level, never the parent's. (The mirror guard on the child
        // stays: asking for level INDIVIDUAL *with* a parent is contradictory, and depth is still capped, so a
        // CONTACT parent is still rejected above.)
        if (INDIVIDUAL.equals(parent.getAccountLevel())) {
            parent.setAccountLevel(COMPANY);
            repo.save(parent);
        }

        child.setParentPartyId(parentId);
        child.setAccountLevel(level);
        return toDto(repo.save(child));
    }

    /**
     * Walk UP from the proposed parent: if we meet the child, this edit would close a loop. Bounded by MAX_DEPTH
     * plus a slack step so a tree already corrupted by a bad migration terminates with an error instead of hanging.
     */
    private void assertNoCycle(Long childId, Party parent, Long org, Long user) {
        Party cursor = parent;
        for (int hops = 0; cursor != null && hops <= MAX_DEPTH + 1; hops++) {
            if (childId.equals(cursor.getId()))
                throw new IllegalArgumentException("That would make the account a descendant of itself.");
            Long up = cursor.getParentPartyId();
            cursor = (up == null) ? null : repo.findByIdScoped(up, org, user).orElse(null);
        }
    }

    /** Depth of the parent chain, so the new child does not land below COMPANY → BRANCH → CONTACT. */
    private void assertDepthWithinCap(Party parent, Long org, Long user) {
        int depth = 1;   // the child being placed
        Party cursor = parent;
        while (cursor != null && depth <= MAX_DEPTH) {
            depth++;
            Long up = cursor.getParentPartyId();
            cursor = (up == null) ? null : repo.findByIdScoped(up, org, user).orElse(null);
        }
        if (depth > MAX_DEPTH)
            throw new IllegalArgumentException("Accounts nest at most three deep (company → branch → contact).");
    }

    private static String normaliseLevel(String requested, String current) {
        String v = (requested == null || requested.isBlank())
                ? (current == null ? INDIVIDUAL : current)
                : requested.trim().toUpperCase();
        return switch (v) {
            case COMPANY, BRANCH, CONTACT, INDIVIDUAL -> v;
            default -> throw new IllegalArgumentException(
                    "Unknown account level: " + requested + " (expected COMPANY, BRANCH, CONTACT or INDIVIDUAL).");
        };
    }

    /** A party's direct children — the account tree screen reads one level at a time. */
    @Transactional(readOnly = true)
    public List<PartyDTO> children(Long parentId) {
        return repo.findChildrenScoped(parentId, CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toDto).toList();
    }

    /** Every company/root that heads a hierarchy in this tenant (plain individuals excluded). */
    @Transactional(readOnly = true)
    public List<PartyDTO> accountRoots() {
        return repo.findAccountRootsScoped(CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toDto).toList();
    }

    /**
     * The whole subtree under {@code rootId}, INCLUDING the root — what business-service re-stamps after a
     * hierarchy edit, and what a group statement would sum over. Depth-capped, so a malformed tree terminates.
     */
    @Transactional(readOnly = true)
    public List<PartyDTO> subtree(Long rootId) {
        Long org = CurrentUser.organizationId(), user = CurrentUser.userId();
        Party root = repo.findByIdScoped(rootId, org, user).orElse(null);
        if (root == null) return List.of();

        List<Party> out = new java.util.ArrayList<>();
        out.add(root);
        List<Party> frontier = List.of(root);
        for (int depth = 1; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<Party> next = new java.util.ArrayList<>();
            for (Party p : frontier) next.addAll(repo.findChildrenScoped(p.getId(), org, user));
            out.addAll(next);
            frontier = next;
        }
        return out.stream().map(this::toDto).toList();
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
        String module = role.getModule().trim().toLowerCase(), r = role.getRole().trim().toUpperCase();
        // DR-1: a link MOVES - a record re-linked to this partner no longer belongs to the one it had before.
        linkRepo.deleteLinksOnOtherParties(p.getOrganizationId(), p.getId(), module, r, role.getLocalId());
        linkRepo.upsertLink(p.getOrganizationId(), p.getId(), module, r, role.getLocalId(), truncate(role.getLabel(), 160));
    }

    /**
     * DR-1 — partners that share a phone key or a tax key with another partner in this org, grouped by that key, each
     * with its roles. Read-only: joining two is a deliberate owner action (DR-2), never done here.
     */
    @Transactional(readOnly = true)
    public List<PartyDuplicateGroupDTO> possibleDuplicates() {
        List<Party> rows = repo.findPossibleDuplicates(CurrentUser.organizationId());
        Map<String, List<Party>> byKey = new java.util.LinkedHashMap<>();
        for (Party p : rows) {
            if (p.getTaxKey() != null && rows.stream().filter(q -> p.getTaxKey().equals(q.getTaxKey())).count() > 1)
                byKey.computeIfAbsent("TAX:" + p.getTaxKey(), k -> new java.util.ArrayList<>()).add(p);
            if (p.getContactKey() != null && rows.stream().filter(q -> p.getContactKey().equals(q.getContactKey())).count() > 1)
                byKey.computeIfAbsent("PHONE:" + p.getContactKey(), k -> new java.util.ArrayList<>()).add(p);
        }
        List<PartyDuplicateGroupDTO> out = new java.util.ArrayList<>();
        byKey.forEach((k, parties) -> out.add(PartyDuplicateGroupDTO.builder()
                .keyType(k.substring(0, k.indexOf(':'))).matchKey(k.substring(k.indexOf(':') + 1))
                .parties(parties.stream().map(p -> contactView(p.getId())).filter(java.util.Objects::nonNull).toList())
                .build()));
        return out;
    }

    private static String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max);
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    /**
     * NOTE: {@code parentPartyId} and {@code accountLevel} are deliberately NOT applied here. They have exactly one
     * write path — {@link #setAccountParent} — because they are the only fields with cross-row invariants (cycles,
     * depth, same-tenant parent). Letting a generic update set them would route around every one of those guards;
     * demoting a COMPANY to INDIVIDUAL through here would orphan its branches.
     */
    private void apply(Party p, PartyDTO dto) {
        if (dto.getName() != null && !dto.getName().isBlank()) p.setName(dto.getName().trim());
        if (dto.getPartyType() != null) p.setPartyType(dto.getPartyType().trim().toUpperCase());
        p.setContact(blankToNull(dto.getContact()));
        p.setEmail(blankToNull(dto.getEmail()));
        if (dto.getTaxId() != null) p.setTaxKey(com.myplus.common.web.PartyKeys.taxKey(dto.getTaxId()));
        p.setAddress(dto.getAddress());
        p.setNotes(dto.getNotes());
        if (dto.getActive() != null) p.setActive(dto.getActive());
    }

    /** On an upsert match, only enrich empty fields — never overwrite existing identity with a blank. */
    private void fillBlanks(Party p, PartyDTO dto) {
        if (isBlank(p.getName()) && !isBlank(dto.getName())) p.setName(dto.getName().trim());
        if (isBlank(p.getEmail()) && !isBlank(dto.getEmail())) p.setEmail(dto.getEmail().trim());
        if (isBlank(p.getContact()) && !isBlank(dto.getContact())
                && repo.findByOrgAndContact(p.getOrganizationId(), dto.getContact().trim()).isEmpty())
            p.setContact(dto.getContact().trim());
        if (isBlank(p.getTaxKey())) p.setTaxKey(com.myplus.common.web.PartyKeys.taxKey(dto.getTaxId()));
        if (isBlank(p.getAddress()) && !isBlank(dto.getAddress())) p.setAddress(dto.getAddress());
        if (isBlank(p.getPartyType()) && !isBlank(dto.getPartyType())) p.setPartyType(dto.getPartyType().trim().toUpperCase());
    }

    private PartyDTO toDto(Party p) {
        return PartyDTO.builder()
                .id(p.getId()).partyType(p.getPartyType()).name(p.getName())
                .contact(p.getContact()).email(p.getEmail()).address(p.getAddress())
                .notes(p.getNotes()).active(p.getActive())
                .parentPartyId(p.getParentPartyId()).accountLevel(p.getAccountLevel())
                .build();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
}
