package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;

import com.myplus.commerce.domain.Money;

/**
 * Splits what the customer paid for one line into the seller's payable and every deduction, and proves the split
 * is complete (source §15):
 *
 * <pre>
 *   customer amount = merchant payable + commission + delivery + processing fees + taxes + reserves + adjustments
 * </pre>
 *
 * <p>The payable is DERIVED as the remainder, so the identity holds by construction; {@link Breakdown#reconciles()}
 * re-adds the parts so a future edit that stores the payable separately is caught by the tests that call it.
 * A negative payable is refused rather than carried: deductions larger than the sale mean the inputs are wrong.
 */
public final class SettlementCalculator {

    private SettlementCalculator() {
    }

    public record Inputs(BigDecimal customerAmount, CommissionPolicy commission, BigDecimal deliveryFeeRetained,
            BigDecimal processingFee, BigDecimal tax, BigDecimal reserve, BigDecimal adjustment) {
    }

    public record Breakdown(BigDecimal customerAmount, BigDecimal commission, BigDecimal deliveryFee,
            BigDecimal processingFee, BigDecimal tax, BigDecimal reserve, BigDecimal adjustment,
            BigDecimal merchantPayable) {

        public boolean reconciles() {
            BigDecimal parts = merchantPayable.add(commission).add(deliveryFee).add(processingFee).add(tax)
                    .add(reserve).add(adjustment);
            return customerAmount.compareTo(parts) == 0;
        }
    }

    public static Breakdown calculate(Inputs in) {
        BigDecimal amount = Money.nz(in.customerAmount());
        if (amount.signum() < 0)
            throw new MarketplaceRuleException("NEGATIVE_AMOUNT", "The customer amount cannot be negative.");
        BigDecimal delivery = Money.nz(in.deliveryFeeRetained());
        BigDecimal commission = in.commission() == null ? Money.ZERO : in.commission().commissionOn(amount, delivery);
        BigDecimal processing = Money.nz(in.processingFee());
        BigDecimal tax = Money.nz(in.tax());
        BigDecimal reserve = Money.nz(in.reserve());
        BigDecimal adjustment = Money.nz(in.adjustment());
        BigDecimal payable = amount.subtract(commission).subtract(delivery).subtract(processing).subtract(tax)
                .subtract(reserve).subtract(adjustment);
        if (payable.signum() < 0)
            throw new MarketplaceRuleException("NEGATIVE_PAYABLE",
                    "The deductions on this line are larger than what the customer paid.");
        return new Breakdown(amount, commission, delivery, processing, tax, reserve, adjustment, payable);
    }
}
