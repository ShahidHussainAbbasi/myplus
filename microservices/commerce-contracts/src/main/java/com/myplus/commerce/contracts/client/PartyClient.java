package com.myplus.commerce.contracts.client;

import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * Declarative client for the shared party/contact master. A module calls {@link #upsert} on write (find-or-create by
 * de-dup key → a stable partyId) so the same person resolves to ONE identity across modules. The implementing proxy
 * is built from a load-balanced RestClient (base URL {@code lb://party-service/api/party/parties}) in the consuming
 * service (see its TradeClientsConfig). Best-effort at the call site — a party hiccup must never fail the domain write.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface PartyClient {

    /** Find-or-create a party by de-dup key (contact, then email); returns it with its partyId. */
    @PostExchange("/upsert")
    PartyRef upsert(@RequestBody PartyRef request);

    /** Resolve a party by its id. */
    @GetExchange("/{id}")
    PartyRef get(@PathVariable("id") Long id);

    /**
     * Record a role link for a party whose id is ALREADY known — used by the per-module backfill (rows bridged before
     * P4 have a party_id but no link, and the skip-guard means they never bridge again). Idempotent server-side.
     */
    @PostExchange("/{id}/roles")
    void link(@PathVariable("id") Long id, @RequestBody PartyRoleRef role);

    /**
     * Bulk variant for the backfill: ONE call per batch instead of one per row (a per-row call would make backfilling
     * a large customer table N round trips). Each item carries its own {@code partyId}. Returns the number linked.
     */
    @PostExchange("/roles/bulk")
    Integer linkBulk(@RequestBody java.util.List<PartyRoleRef> links);

    /**
     * Phase 4a — place a party in the account hierarchy (company → branch → contact), or detach it with a null
     * parent. The only write path for the hierarchy; every invariant (same tenant, no cycles, depth cap) is
     * enforced server-side, so a rejection arrives as a 400 the caller should surface verbatim to the operator.
     */
    /**
     * NOTE {@code required = false} on both params. A Spring HTTP interface treats {@code @RequestParam} as
     * REQUIRED by default and throws {@code "Missing request parameter value 'parentId'"} client-side — before
     * any request is sent — when the argument is null. Detaching an account is exactly the null-parent case, so
     * without this the detach path could never reach party-service at all.
     */
    @PutExchange("/{id}/account-parent")
    PartyRef setAccountParent(@PathVariable("id") Long id,
                              @RequestParam(name = "parentId", required = false) Long parentId,
                              @RequestParam(name = "accountLevel", required = false) String accountLevel);

    /**
     * The subtree under a party, INCLUDING it. business-service calls this after a hierarchy edit to re-stamp the
     * credit account on every affected customer — one call, rather than walking the tree a level per round trip.
     */
    @GetExchange("/{id}/subtree")
    java.util.List<PartyRef> subtree(@PathVariable("id") Long id);
}
