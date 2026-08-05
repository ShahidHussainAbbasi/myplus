package com.myplus.business_service.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/**
 * B2B Phase 4a — the B2B account hierarchy as business-service sees it.
 *
 * <h3>Why this orchestration lives here and not in party-service</h3>
 * The hierarchy itself (company → branch → contact) is identity structure and belongs to party-service, which is
 * why Education sponsors and Welfare donors will get it for free. But the CREDIT consequence of a hierarchy edit
 * is business-service's alone, and it must be applied in the same operator action — otherwise a branch could be
 * attached to a company and keep drawing on its own limit until some later sweep noticed.
 *
 * <p>So one call does both: set the parent in party-service, then re-stamp {@code creditAccountCustomerId} across
 * the affected subtree. party-service stays ignorant of credit; business-service stays the only writer of its own
 * credit columns.
 *
 * <h3>Why stamped rather than resolved</h3>
 * {@code assertCreditPolicy} runs on the sell path. Resolving "whose limit governs this branch?" there would put
 * a party-service round trip on the hottest path in the POS. The hierarchy changes rarely; the stamp is rewritten
 * then. Same rule the product last-rates slice established: write it when the source changes, never derive it on
 * the read path.
 *
 * <p><b>Not best-effort.</b> Unlike the identity bridge, this runs inline and propagates failure: a hierarchy edit
 * that silently half-applied would leave branches drawing on the wrong limit, and a wrong credit ceiling is a
 * money decision, not a display glitch.
 */
@Service
@RequiredArgsConstructor
public class CustomerAccountService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerAccountService.class);

    /** Only used to read a rejection body; thread-safe once configured, so one instance is enough. */
    private static final com.fasterxml.jackson.databind.ObjectMapper ERROR_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final CustomerRepo customerRepo;
    private final RequestUtil requestUtil;

    /**
     * Optional, exactly as {@link PartyBridgeService} treats it: party-service may be unwired in a deployment,
     * and a B2B grouping feature must not be able to stop this service from STARTING. A required constructor
     * dependency here would turn "party-service is not deployed" into "business-service will not boot".
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PartyClient partyClient;

    private Long orgId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private Long userId() {
        AuthenticatedUser u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }

    /**
     * A newly created customer is its OWN credit account until an owner puts it in a group.
     *
     * <p>Must be stamped at creation, not left null: the shared-pool exposure is SUM(due) over the rows pointing
     * at an account, so a null here would match no rows, report a zero balance and wave every sale through the
     * credit check. V36 backfills existing rows for exactly the same reason.
     *
     * <p><b>Transactional because the stamp is a {@code @Modifying} update</b> — one with no active transaction
     * throws {@code TransactionRequiredException}. Every other targeted update on this repo owns a boundary the
     * same way ({@code JpaStoreCreditStore.cacheBalance} is transactional; the party bridge stamps inside a
     * {@code REQUIRES_NEW} after-commit handler). It lives here rather than on the controller so that
     * {@code addCustomer}'s party bridge keeps deferring its HTTP call past commit instead of running inside a
     * widened transaction holding a DB connection.
     */
    @Transactional
    public void stampSelfAsCreditAccount(Customer saved) {
        if (saved == null || saved.getCustomerId() == null || saved.getCreditAccountCustomerId() != null) return;
        customerRepo.updateCreditAccount(saved.getCustomerId(), saved.getCustomerId());
        saved.setCreditAccountCustomerId(saved.getCustomerId());   // keep the caller's copy honest
    }


    /**
     * Attach a customer to a parent account (or detach it with a null parent), then re-stamp the credit account
     * across everything affected.
     *
     * <p><b>Only the MOVED subtree changes account.</b> When a branch (with its contacts) leaves company A for
     * company X, that branch and its descendants move to X's account; A's other members still point at A and
     * need no touching. The new account is the parent's OWN account head — not the parent itself — so attaching
     * a contact under a branch puts it on the COMPANY's limit rather than splitting the pool at the branch.
     *
     * @return how many customer rows were re-stamped
     * @throws IllegalArgumentException with party-service's own message when a guard rejects the move
     */
    @Transactional
    public int setAccountParent(Long customerId, Long parentCustomerId, String accountLevel) {
        if (partyClient == null)
            throw new IllegalArgumentException(
                    "The contact master is not available, so accounts cannot be grouped right now.");
        Customer child = load(customerId);
        if (child.getPartyId() == null)
            throw new IllegalArgumentException(
                    "This customer is not linked to the contact master yet, so it cannot join a group. "
                  + "Save the customer again to link it, then retry.");

        Long parentPartyId = null;
        // The account the moved subtree lands on: the parent's OWN head (so a contact under a branch draws on
        // the COMPANY, not on the branch), or the customer itself when detaching.
        Long newAccountId = child.getCustomerId();
        if (parentCustomerId != null) {
            Customer parent = load(parentCustomerId);
            if (parent.getPartyId() == null)
                throw new IllegalArgumentException("The parent customer is not linked to the contact master yet.");
            parentPartyId = parent.getPartyId();
            newAccountId = parent.getCreditAccountCustomerId() != null
                    ? parent.getCreditAccountCustomerId()
                    : parent.getCustomerId();
        }

        // party-service owns the invariants (same tenant, no cycles, depth cap). Its rejection is the operator's
        // answer, so it is surfaced verbatim rather than reworded here — two copies of a rule drift apart.
        //
        // PartyClient is a Spring HTTP interface, so a guard rejection arrives as a 4xx EXCEPTION, not as a
        // return value. Without this translation the controller's IllegalArgumentException branch never fires,
        // the generic handler takes over, and "that would make the account a descendant of itself" reaches the
        // operator as "Could not update the account hierarchy."
        try {
            partyClient.setAccountParent(child.getPartyId(), parentPartyId, accountLevel);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new IllegalArgumentException(guardMessageFrom(e), e);
        }

        // Walk the MOVED subtree — rooted at the customer itself, since it and its descendants are exactly the
        // rows whose account changes.
        return restampSubtree(child.getPartyId(), newAccountId);
    }

    /**
     * Pull party-service's operator-facing reason out of a 4xx body ({@code {"message": "..."}}).
     *
     * <p>Falls back to a generic sentence rather than leaking a raw body or a status code: the caller shows this
     * to a person, and "400 Bad Request" tells them nothing about what to do differently.
     */
    private String guardMessageFrom(org.springframework.web.client.RestClientResponseException e) {
        try {
            String body = e.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                com.fasterxml.jackson.databind.JsonNode node = ERROR_MAPPER.readTree(body);
                String msg = node.path("message").asText(null);
                if (msg != null && !msg.isBlank()) return msg;
            }
        } catch (Exception ignored) {
            // A non-JSON or unexpected body is not worth failing over — fall through to the generic wording.
        }
        LOG.warn("Account hierarchy: party-service rejected the change with {} and no readable message",
                e.getStatusCode());
        return "That account change was not allowed.";
    }

    /**
     * Stamp {@code accountCustomerId} onto every bridged customer in the party subtree rooted at
     * {@code rootPartyId} — the root itself included.
     *
     * <p>The account is passed IN rather than derived from the root. Deriving it made the root of the moved
     * subtree its own account head, which is right for a detach but wrong for an attach: a contact moved under a
     * branch would have been stamped onto the BRANCH instead of the branch's company, splitting one credit pool
     * into two at depth 3.
     *
     * <p>One party-service call for the whole tree, one scoped query to map parties → customers, then targeted
     * column updates. A party in the subtree with no bridged customer is skipped, not an error — the hierarchy is
     * shared across modules, so a branch may legitimately exist for Education and not for POS.
     */
    private int restampSubtree(Long rootPartyId, Long accountCustomerId) {
        if (rootPartyId == null || accountCustomerId == null || partyClient == null) return 0;

        // Every early return below is a SILENT no-op that leaves a branch drawing on the wrong credit limit, so
        // each one warns with the numbers that identify which it was. A re-stamp that stamps nothing is an
        // anomaly worth surfacing in its own right, not debug noise.
        List<PartyRef> subtree = partyClient.subtree(rootPartyId);
        if (subtree == null || subtree.isEmpty()) {
            LOG.warn("Account hierarchy: subtree of party {} came back {}; nothing to re-stamp",
                    rootPartyId, subtree == null ? "null" : "empty");
            return 0;
        }

        List<Long> partyIds = subtree.stream().map(PartyRef::getId).filter(java.util.Objects::nonNull).toList();
        LOG.debug("Account hierarchy: subtree of party {} returned {} row(s), {} with an id: {}",
                rootPartyId, subtree.size(), partyIds.size(), partyIds);
        if (partyIds.isEmpty()) {
            LOG.warn("Account hierarchy: subtree of party {} carried no usable ids; nothing to re-stamp", rootPartyId);
            return 0;
        }

        List<Customer> scoped = customerRepo.findByPartyIdsScoped(partyIds, orgId(), userId());
        Map<Long, Customer> byParty = scoped.stream()
                .collect(Collectors.toMap(Customer::getPartyId, Function.identity(), (a, b) -> a));

        if (byParty.isEmpty()) {
            LOG.warn("Account hierarchy: none of the {} party/parties under {} has a bridged customer in this "
                    + "tenant; nothing to re-stamp", partyIds.size(), rootPartyId);
            return 0;
        }
        final Long accountId = accountCustomerId;

        int n = 0;
        for (Long partyId : partyIds) {
            Customer c = byParty.get(partyId);
            if (c == null) continue;
            if (accountId.equals(c.getCreditAccountCustomerId())) continue;   // already correct
            customerRepo.updateCreditAccount(c.getCustomerId(), accountId);
            c.setCreditAccountCustomerId(accountId);   // keep the in-memory copy honest for the caller
            n++;
        }
        LOG.info("Account hierarchy: re-stamped {} customer(s) onto credit account {}", n, accountId);
        return n;
    }

    /**
     * The account group for a customer: the head plus everyone drawing on it, with the pooled exposure. Powers the
     * account-tree screen and the "who else draws on this limit?" answer an operator needs before raising a limit.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> accountGroup(Long customerId) {
        Customer c = load(customerId);
        Long accountId = c.getCreditAccountCustomerId() != null ? c.getCreditAccountCustomerId() : c.getCustomerId();
        Customer head = customerRepo.findById(accountId).orElse(c);

        List<Map<String, Object>> members = new ArrayList<>();
        for (Customer m : customerRepo.findScoped(orgId(), userId())) {
            if (accountId.equals(m.getCreditAccountCustomerId())) {
                members.add(Map.of(
                        "customerId", m.getCustomerId(),
                        "name", m.getName() == null ? "" : m.getName(),
                        "dueAmount", m.getDueAmount() == null ? java.math.BigDecimal.ZERO : m.getDueAmount(),
                        "isHead", accountId.equals(m.getCustomerId())));
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("accountCustomerId", accountId);
        out.put("accountName", head.getName() == null ? "" : head.getName());
        out.put("creditLimit", head.getCreditLimit());          // null = no limit on the group
        out.put("pooledDue", customerRepo.sumDueByCreditAccount(accountId, orgId(), userId()));
        out.put("members", members);
        return out;
    }

    /**
     * Trade customers that never bridged to a party and therefore cannot join a hierarchy. Surfaced rather than
     * silently omitted — the programme plan flags best-effort {@code party_id} bridging as the risk that would
     * otherwise make a group's exposure quietly incomplete.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> unbridged() {
        return customerRepo.findUnbridgedScoped(orgId(), userId()).stream()
                .map(c -> Map.<String, Object>of(
                        "customerId", c.getCustomerId(),
                        "name", c.getName() == null ? "" : c.getName(),
                        "customerType", c.getCustomerType() == null ? "" : c.getCustomerType().name()))
                .toList();
    }

    private Customer load(Long customerId) {
        Customer c = (customerId == null) ? null : customerRepo.findById(customerId).orElse(null);
        // Anti-IDOR: a customer outside the caller's tenant is reported exactly like one that does not exist.
        if (c == null || !inMyTenant(c)) throw new IllegalArgumentException("Customer not found: " + customerId);
        return c;
    }

    private boolean inMyTenant(Customer c) {
        Long org = orgId();
        return (c.getOrganizationId() != null && c.getOrganizationId().equals(org))
                || (c.getOrganizationId() == null && c.getUserId() != null && c.getUserId().equals(userId()));
    }
}
