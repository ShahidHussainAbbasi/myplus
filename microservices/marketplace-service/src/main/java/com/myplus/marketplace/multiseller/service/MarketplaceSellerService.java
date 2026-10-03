package com.myplus.marketplace.multiseller.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.domain.MarketplaceRuleException;
import com.myplus.marketplace.multiseller.domain.MarketplaceStateMachines;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.dto.SellerDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceAgreementAcceptance;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceAgreementAcceptanceRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

import lombok.RequiredArgsConstructor;

/**
 * MKT-0a — seller onboarding: agreements, the application, and the operator's decision.
 *
 * <h3>Three conditions to sell, each owned by a different party</h3>
 * <ol>
 *   <li>the {@code marketplaceSelling} capability — the OWNER's switch, bounded by the PLAN;</li>
 *   <li>the current agreements accepted — the OWNER's (or an admin's) signature;</li>
 *   <li>an APPROVED seller account — MAXTHESERVICE's decision.</li>
 * </ol>
 * {@link #assertActiveSeller} checks all three, and is the one guard every later seller write calls.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceSellerService {

    static final int MAX_PAGE = 100;

    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplaceAgreementAcceptanceRepository acceptances;
    private final SellerAccess access;
    /** MKT-1c — a seller decision re-publishes that seller's offers (a suspended seller disappears at once). */
    private final OfferProjectionService projection;

    // ── tenant ─────────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SellerDTOs.SellerView view() {
        Long org = access.org();
        List<SellerDTOs.Agreement> agreements = acceptances.findByOrganizationIdOrderByAcceptedAtDesc(org).stream()
                .map(a -> new SellerDTOs.Agreement(a.getAgreementCode(), a.getAgreementVersion(),
                        a.getAcceptedByUserId(), a.getAcceptedAt()))
                .toList();
        boolean current = agreementsCurrent(org);
        SellerDTOs.Account account = accounts.findByOrganizationId(org).map(MarketplaceSellerService::toDto).orElse(null);
        boolean capability = access.capabilityOn();
        boolean canSell = capability && current && account != null
                && SellerAccount.APPROVED.name().equals(account.status());
        return new SellerDTOs.SellerView(capability, MarketplaceAgreements.CURRENT_VERSION, agreements, current,
                account, canSell);
    }

    /**
     * Accept both current agreements and apply (or re-apply after a rejection). Idempotent: accepting the same
     * version again changes nothing and answers the same way, so a double click is harmless.
     */
    @Transactional
    public SellerDTOs.Acceptance accept(SellerDTOs.AcceptRequest req) {
        access.assertCapabilityOn();
        access.assertOwnerOrAdmin();
        Long org = access.org();
        Long user = access.userId();
        String version = req == null ? null : trim(req.version());
        if (!MarketplaceAgreements.CURRENT_VERSION.equals(version))
            throw new ValidationException("The marketplace agreements have changed. Reload the page and read version "
                    + MarketplaceAgreements.CURRENT_VERSION + " before accepting.");

        MarketplaceSellerAccount account = accounts.findByOrganizationId(org).orElse(null);
        String name = trim(req.displayName());
        boolean applying = account == null || SellerAccount.REJECTED.name().equals(account.getStatus());
        if (applying && (name == null || name.length() < 2 || name.length() > 120))
            throw new ValidationException("Enter the name customers will see for your business (2 to 120 characters).");

        LocalDateTime now = LocalDateTime.now();
        MarketplaceAgreementAcceptance first = null;
        for (String code : MarketplaceAgreements.REQUIRED) {
            MarketplaceAgreementAcceptance a = acceptances
                    .findByOrganizationIdAndAgreementCodeAndAgreementVersion(org, code, version)
                    .orElseGet(() -> insertAcceptance(org, code, version, user, now));
            if (first == null) first = a;
        }

        if (account == null) {
            account = new MarketplaceSellerAccount();
            account.setOrganizationId(org);
            account.setStatus(SellerAccount.PENDING_APPROVAL.name());
            account.setDisplayName(name);
            account.setAppliedByUserId(user);
            account.setAppliedAt(now);
            account = accounts.save(account);
        } else if (applying) {
            move(account, SellerAccount.PENDING_APPROVAL);
            account.setDisplayName(name);
            account.setStatusReason(null);
            account.setAppliedByUserId(user);
            account.setAppliedAt(now);
            account = accounts.save(account);
        }
        return new SellerDTOs.Acceptance(first.getAgreementVersion(), first.getAcceptedByUserId(),
                first.getAcceptedAt(), toDto(account));
    }

    /** The guard every later seller write calls (MKT-1b onward). Each refusal names what is missing. */
    @Transactional(readOnly = true)
    public void assertActiveSeller() {
        access.assertCapabilityOn();
        Long org = access.org();
        if (!agreementsCurrent(org))
            throw new ValidationException("Accept the marketplace seller and data-sharing agreements (version "
                    + MarketplaceAgreements.CURRENT_VERSION + ") before selling.");
        MarketplaceSellerAccount account = accounts.findByOrganizationId(org).orElse(null);
        if (account == null || !SellerAccount.APPROVED.name().equals(account.getStatus()))
            throw new ValidationException(account == null
                    ? "Apply to sell on the marketplace first."
                    : SellerAccount.PENDING_APPROVAL.name().equals(account.getStatus())
                            ? "MaxTheService is still reviewing your seller account."
                            : "Your seller account is " + account.getStatus().toLowerCase().replace('_', ' ')
                                    + (account.getStatusReason() != null ? ": " + account.getStatusReason() : "."));
    }

    // ── operator ───────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<SellerDTOs.Account> list(String status, Integer page, Integer size) {
        access.assertOperator();
        PageRequest p = PageRequest.of(page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? 50 : Math.min(size, MAX_PAGE));
        Page<MarketplaceSellerAccount> rows = status == null || status.isBlank()
                ? accounts.findAllByOrderByAppliedAtDesc(p)
                : accounts.findByStatusOrderByAppliedAtAsc(parse(status).name(), p);
        return PageResponse.of(rows, MarketplaceSellerService::toDto);
    }

    @Transactional
    public SellerDTOs.Account decide(Long sellerOrg, SellerDTOs.DecisionRequest req) {
        access.assertOperator();
        if (req == null || req.decision() == null) throw new ValidationException("Choose a decision.");
        SellerAccount target = switch (req.decision().trim().toUpperCase()) {
            case "APPROVE", "REINSTATE" -> SellerAccount.APPROVED;
            case "REJECT" -> SellerAccount.REJECTED;
            case "SUSPEND" -> SellerAccount.SUSPENDED;
            default -> throw new ValidationException("Unknown decision: " + req.decision());
        };
        String reason = trim(req.reason());
        if ((target == SellerAccount.REJECTED || target == SellerAccount.SUSPENDED) && reason == null)
            throw new ValidationException("Give the seller a reason. They will see it as written.");
        MarketplaceSellerAccount account = accounts.findByOrganizationId(sellerOrg)
                .orElseThrow(() -> new ResourceNotFoundException("No seller account for that business."));
        if (req.version() != null && !req.version().equals(account.getVersion()))
            throw new org.springframework.dao.OptimisticLockingFailureException("seller account changed");
        move(account, target);
        account.setStatusReason(target == SellerAccount.APPROVED ? null : reason);
        account.setDecidedByUserId(access.userId());
        account.setDecidedAt(LocalDateTime.now());
        SellerDTOs.Account out = toDto(accounts.save(account));
        projection.publishSeller(sellerOrg);   // same transaction: the public read can never show a suspended seller
        return out;
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────────

    private boolean agreementsCurrent(Long org) {
        for (String code : MarketplaceAgreements.REQUIRED) {
            if (acceptances.findByOrganizationIdAndAgreementCodeAndAgreementVersion(org, code,
                    MarketplaceAgreements.CURRENT_VERSION).isEmpty()) return false;
        }
        return true;
    }

    private MarketplaceAgreementAcceptance insertAcceptance(Long org, String code, String version, Long user,
            LocalDateTime now) {
        MarketplaceAgreementAcceptance a = new MarketplaceAgreementAcceptance();
        a.setOrganizationId(org);
        a.setAgreementCode(code);
        a.setAgreementVersion(version);
        a.setAcceptedByUserId(user);
        a.setAcceptedAt(now);
        try {
            return acceptances.saveAndFlush(a);
        } catch (DataIntegrityViolationException race) {
            // a concurrent accept of the same version won the UNIQUE key: theirs is the record, and it is the same fact
            return acceptances.findByOrganizationIdAndAgreementCodeAndAgreementVersion(org, code, version).orElseThrow();
        }
    }

    private static void move(MarketplaceSellerAccount account, SellerAccount to) {
        try {
            MarketplaceStateMachines.SELLER_ACCOUNT.transition(SellerAccount.valueOf(account.getStatus()), to);
        } catch (MarketplaceRuleException e) {
            throw new ValidationException("A seller account that is "
                    + account.getStatus().toLowerCase().replace('_', ' ') + " cannot be moved to "
                    + to.name().toLowerCase().replace('_', ' ') + ".");
        }
        account.setStatus(to.name());
    }

    private static SellerAccount parse(String status) {
        try {
            return SellerAccount.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown seller status: " + status);
        }
    }

    static SellerDTOs.Account toDto(MarketplaceSellerAccount a) {
        return new SellerDTOs.Account(a.getOrganizationId(), a.getDisplayName(), a.getStatus(), a.getStatusReason(),
                a.getAppliedAt(), a.getDecidedAt(), a.getVersion());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
