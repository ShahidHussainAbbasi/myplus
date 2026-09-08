package com.myplus.auth.service;

import com.myplus.auth.entity.Permission;
import com.myplus.auth.entity.PermissionSet;
import com.myplus.auth.entity.UserPermissionSet;
import com.myplus.auth.repository.*;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * PERM-1 — permission sets: the one place that answers "what may this member do?".
 *
 * <p>Design: {@code microservices/docs/slices/perm-1-permission-sets-design.md}.
 *
 * <h3>Where this sits</h3>
 * Permissions are minted into the EXISTING {@code privileges} JWT claim rather than a new one, because
 * {@code hasAuthority(...)} already reads that claim end to end — gateway, {@code CurrentUser},
 * {@code @PreAuthorize}, and Thymeleaf's {@code sec:authorize}. A second claim would have meant a second
 * rail to keep in step with the first, and the two would eventually disagree.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository permissionRepo;
    private final PermissionSetRepository setRepo;
    private final PermissionSetItemRepository itemRepo;
    private final UserPermissionSetRepository userSetRepo;

    public static final String SET_STANDARD = "Standard";

    // ── the catalog ────────────────────────────────────────────────────────────────────────────

    /** Every permission, in matrix order. The screen renders from this, never from a list of its own. */
    public List<Permission> catalog() {
        return permissionRepo.findAllByOrderBySortOrderAsc();
    }

    /**
     * ⭐ THE CLOSURE — and the reason it is computed HERE and not only in the browser.
     *
     * <p>Granting {@code sale.create} without {@code product.view} produces a sale screen whose item
     * picker is EMPTY, with no error and nothing on screen explaining why. That is the failure this
     * codebase keeps paying for: a state that is silently broken rather than loudly refused.
     *
     * <p>The browser cascades too, so the owner sees what is happening as they tick. But a browser is a
     * convenience and an API caller is not obliged to use one, so the authoritative closure runs on the
     * way in. Both directions of the cascade are the UI's job; storing a coherent set is this method's.
     *
     * <p>Transitive, deliberately: {@code opening.create} implies {@code customer.view}, and if
     * {@code customer.view} ever gains an implication of its own this picks it up with no edit here.
     */
    public Set<String> close(Collection<String> codes) {
        Map<String, Permission> byCode = new HashMap<>();
        for (Permission p : catalog()) byCode.put(p.getCode(), p);

        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(codes == null ? List.of() : codes);
        while (!queue.isEmpty()) {
            String code = queue.poll();
            if (code == null || code.isBlank() || !out.add(code.trim())) continue;  // already closed over
            Permission p = byCode.get(code.trim());
            if (p == null || p.getImplies() == null || p.getImplies().isBlank()) continue;
            for (String implied : p.getImplies().split(",")) {
                String t = implied.trim();
                if (!t.isEmpty() && !out.contains(t)) queue.add(t);
            }
        }
        // Unknown codes are dropped rather than stored: a permission that no longer exists in the
        // catalog would sit in the set for ever, granting nothing and confusing every later reader.
        out.retainAll(byCode.keySet());
        return out;
    }

    // ── sets ───────────────────────────────────────────────────────────────────────────────────

    public List<PermissionSet> setsFor(Long orgId) {
        return setRepo.findVisibleTo(orgId);
    }

    public List<String> codesOf(Long setId) {
        return itemRepo.codesOf(setId);
    }

    /**
     * Create or replace a tenant's own set.
     *
     * <p>⚠ A BUILT-IN IS NEVER EDITED. Standard and Administrator are the contract that this feature's
     * deploy changed nothing for anybody; letting a tenant edit them in place would rewrite that
     * contract retrospectively for every member already migrated onto them. A tenant DUPLICATES a
     * built-in and edits the copy, which is also how they get a sensible starting point.
     */
    @Transactional
    public PermissionSet save(Long orgId, Long setId, String name, String description, String scope,
                              Collection<String> codes) {
        if (name == null || name.isBlank()) throw new ValidationException("Give the set a name.");
        String sc = "ALL".equalsIgnoreCase(String.valueOf(scope)) ? "ALL" : "OWN";

        PermissionSet set;
        if (setId != null) {
            set = setRepo.findScoped(setId, orgId)
                    .orElseThrow(() -> new ValidationException("That permission set was not found."));
            if (set.isBuiltin()) {
                throw new ValidationException(
                        "\"" + set.getName() + "\" is a built-in set and cannot be changed. "
                      + "Duplicate it and edit the copy.");
            }
        } else {
            set = PermissionSet.builder().organizationId(orgId).builtin(false)
                    .createdAt(LocalDateTime.now()).build();
        }
        set.setName(name.trim());
        set.setDescription(description == null ? null : description.trim());
        set.setScope(sc);
        set.setUpdatedAt(LocalDateTime.now());
        set = setRepo.save(set);

        itemRepo.clear(set.getId());
        for (String code : close(codes)) itemRepo.add(set.getId(), code);
        return set;
    }

    /**
     * Put a member on a set.
     *
     * <p>⚠ Takes effect within the ACCESS TOKEN's life — 15 minutes — not instantly, because the
     * permissions travel in the token and the token is not re-minted until it refreshes. Accepted
     * deliberately by the owner: instant revocation costs a database read on every request, and this is
     * a shop's staff list rather than a bank's. Worth saying out loud wherever it is called, because
     * "I removed him and he could still sell" is otherwise reported as a defect.
     */
    @Transactional
    public void assign(Long userId, Long setId, Long orgId) {
        setRepo.findScoped(setId, orgId)
                .orElseThrow(() -> new ValidationException("That permission set was not found."));
        UserPermissionSet row = userSetRepo.findByUserId(userId)
                .orElseGet(() -> UserPermissionSet.builder().userId(userId).build());
        row.setSetId(setId);
        userSetRepo.save(row);
    }

    /** A set nobody is on can go. One that is in use cannot — the members would silently lose access. */
    @Transactional
    public void delete(Long setId, Long orgId) {
        PermissionSet set = setRepo.findScoped(setId, orgId)
                .orElseThrow(() -> new ValidationException("That permission set was not found."));
        if (set.isBuiltin()) throw new ValidationException("A built-in set cannot be deleted.");
        int inUse = userSetRepo.findBySetId(setId).size();
        if (inUse > 0) {
            throw new ValidationException(inUse == 1
                    ? "One member is on this set. Move them to another set first."
                    : inUse + " members are on this set. Move them to another set first.");
        }
        setRepo.deleteById(setId);
    }

    // ── what a member actually holds ───────────────────────────────────────────────────────────

    /**
     * The permission codes to mint into this user's token.
     *
     * <p>Returns EMPTY for a member on no set, and that is correct rather than a gap: the migration put
     * every existing member on one, so an empty result means a member created outside it — and granting
     * nothing is the safe reading. Deny by default is the whole point of a permission model.
     *
     * <p>⚠ An OWNER is not resolved here at all. Their access is implicit and is applied by the caller,
     * so no edit to any set can lock an owner out of their own shop (design G-4).
     */
    public Set<String> effectiveFor(Long userId) {
        return userSetRepo.findByUserId(userId)
                .map(ups -> new LinkedHashSet<>(itemRepo.codesOf(ups.getSetId())))
                .map(codes -> (Set<String>) codes)
                .orElseGet(LinkedHashSet::new);
    }

    /** Everything in the catalog — what an owner holds, without a row anywhere saying so. */
    public Set<String> everything() {
        Set<String> all = new LinkedHashSet<>();
        for (Permission p : catalog()) all.add(p.getCode());
        return all;
    }

    /** The row-level scope of a member's set: OWN or ALL. Absent set reads as the narrower answer. */
    public String scopeFor(Long userId) {
        return userSetRepo.findByUserId(userId)
                .flatMap(ups -> setRepo.findById(ups.getSetId()))
                .map(PermissionSet::getScope)
                .orElse("OWN");
    }

    /** Which set this member is on, or null. What the team table's picker pre-selects from. */
    public Long setIdFor(Long userId) {
        return userSetRepo.findByUserId(userId).map(UserPermissionSet::getSetId).orElse(null);
    }

    /** The set a brand-new member lands on when nobody chose one. */
    public Optional<PermissionSet> standardSet() {
        return setRepo.findByOrganizationIdIsNullAndName(SET_STANDARD);
    }
}
