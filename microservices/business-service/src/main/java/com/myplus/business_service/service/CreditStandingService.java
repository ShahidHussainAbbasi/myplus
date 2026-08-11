package com.myplus.business_service.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.common.credit.CreditLimitPolicy;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.business_service.util.RequestUtil;

import lombok.RequiredArgsConstructor;

/**
 * OMS O7 D2 — what a customer's credit looks like right now, as a READ.
 *
 * <h3>Why this exists</h3>
 * An order booker standing at the counter needs to know the outlet is over its limit <b>before</b> writing the
 * order, not after the warehouse rejects it a day later — that is finding **B3** in the O7 design, and the
 * whole point of moving the check forward is that it saves the trip.
 *
 * <h3>Why the two helpers moved HERE from {@code SagaSellService}</h3>
 * "Which row's limit governs this customer?" and "what does that account's group already owe?" are the two
 * rules the credit check is built on, and they were private to the sale path. Copying them would create a
 * second definition, and the two would answer differently the first time either changed — so the booker would
 * be told one thing at the counter and the sale would enforce another. That is precisely the drift O4 removed
 * when it deleted the browser's rival copy of the transition rules.
 *
 * <p>{@code SagaSellService} now delegates to this class, so there is exactly one definition and the read and
 * the write cannot disagree. The arithmetic itself already lived in {@code common-credit}'s
 * {@link CreditLimitPolicy} and is untouched.
 */
@Service
@RequiredArgsConstructor
public class CreditStandingService {

    private final CustomerRepo customerRepo;
    private final RequestUtil requestUtil;

    /**
     * The customer row whose limit governs — the stamped credit account (B2B-P4a shared pool), or the customer
     * itself.
     *
     * <p>Falls back to the customer when the stamp is missing or unreadable rather than skipping the check: a
     * null credit account must degrade to "cap this customer by their own limit", never to "no limit at all".
     */
    public Customer creditAccountOf(Customer customer) {
        Long accountId = customer.getCreditAccountCustomerId();
        if (accountId == null || accountId.equals(customer.getCustomerId())) return customer;
        return customerRepo.findById(accountId).orElse(customer);
    }

    /**
     * Σ(due) across the credit account's group. Falls back to the buying customer's own due if the sum comes
     * back null — the same fail-toward-checking rule as {@link #creditAccountOf}.
     */
    public BigDecimal groupExposure(Customer creditAccount, Customer customer) {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        BigDecimal pooled = customerRepo.sumDueByCreditAccount(
                creditAccount.getCustomerId(),
                user == null ? null : user.getOrganizationId(),
                user == null ? null : user.getUserId());
        return pooled != null ? pooled : customer.getDueAmount();
    }

    /**
     * What the booker is shown at the counter.
     *
     * <p>Returns {@code null} when there is nothing to say — no customer, or no limit set on the governing
     * account. That is deliberate and matches the write path: a customer with no limit is not "at 0% of 0",
     * they are simply uncapped, and showing them as breached would train bookers to ignore the warning.
     *
     * <p>Reports the GROUP's numbers when the outlet is a branch of a trade group, because that is whose limit
     * actually binds — the branch's own limit is often blank and its own balance is not what breaches.
     */
    public Standing standingFor(Long customerId) {
        if (customerId == null) return null;
        Customer customer = customerRepo.findById(customerId).orElse(null);
        if (customer == null) return null;

        Customer account = creditAccountOf(customer);
        BigDecimal limit = account.getCreditLimit();
        if (limit == null) return null;                     // uncapped — nothing to warn about

        BigDecimal owed = groupExposure(account, customer);
        if (owed == null) owed = BigDecimal.ZERO;
        BigDecimal available = limit.subtract(owed);
        boolean grouped = !account.getCustomerId().equals(customer.getCustomerId());

        return new Standing(
                grouped ? account.getName() : customer.getName(),
                grouped,
                limit,
                owed,
                available,
                available.signum() < 0);
    }

    /**
     * A customer's credit position. {@code available} is deliberately allowed to go NEGATIVE rather than being
     * floored at zero: "you are 12,000 over" is actionable and "you have 0 available" is not.
     */
    public record Standing(String accountName, boolean grouped, BigDecimal creditLimit,
                           BigDecimal owed, BigDecimal available, boolean overLimit) {
    }
}
