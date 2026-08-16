package com.myplus.commerce.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * THE tax arithmetic for the platform — one engine, used by every channel.
 *
 * <p><b>Why this exists.</b> The books and the storefront each had their own. `business-service`
 * (`TaxService`) resolved a rate and computed tax honouring the tenant's tax switch and mode; marketplace's
 * `CheckoutService.totals()` independently did `net × item.taxRate / 100` — no switch, no org default, no
 * INCLUSIVE handling. The two disagreed in the worst possible way: a shop with tax turned OFF was still shown
 * a tax line and quoted a total the invoice then contradicted (quoted 22, invoiced 20). Nobody saw it because
 * both halves were locally correct; the disagreement only exists BETWEEN them.
 *
 * <p>A duplicated RULE is worse than a duplicated function — so the rule lives here once, and both callers
 * apply it. The per-tenant POLICY (is tax on, which mode, what default rate) still belongs to
 * business-service, which owns the books; it travels to other services over the trade contract. This class
 * holds only the maths, which is why it is pure and static and has no Spring in it.
 *
 * <p>Rounding matches {@link Money}: 2dp HALF_UP, so a total computed here can never disagree with a total
 * computed there by a rounding step.
 */
public final class TaxMath {

    private static final int SCALE = Money.SCALE;
    private static final RoundingMode ROUNDING = Money.ROUNDING;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TaxMath() {}

    /**
     * What one line comes to once tax is applied.
     *
     * @param net   the amount excluding tax
     * @param rate  the percentage actually used (0 when tax does not apply)
     * @param tax   the tax amount
     * @param gross net + tax
     */
    public record TaxAmounts(BigDecimal net, BigDecimal rate, BigDecimal tax, BigDecimal gross) {}

    /**
     * The rate to apply: the product's own rate when it has one, otherwise the tenant's default.
     *
     * <p>A product rate of 0 or null is not "zero-rated", it is "unset" — that is the existing behaviour of
     * the books and changing it here would silently re-rate every product that never had one.
     */
    public static BigDecimal resolveRate(BigDecimal productRate, BigDecimal orgDefaultRate) {
        BigDecimal rate = (productRate != null && productRate.signum() > 0)
                ? productRate
                : (orgDefaultRate != null ? orgDefaultRate : BigDecimal.ZERO);
        return rate.signum() < 0 ? BigDecimal.ZERO : rate;
    }

    /**
     * Tax for one line.
     *
     * @param lineAmount the line total AFTER any discount
     * @param rate       the percentage, already resolved by {@link #resolveRate}
     * @param inclusive  true when the price already contains the tax, false when tax is added on top
     */
    public static TaxAmounts compute(BigDecimal lineAmount, BigDecimal rate, boolean inclusive) {
        BigDecimal amount = lineAmount != null ? lineAmount : BigDecimal.ZERO;
        BigDecimal r = (rate != null && rate.signum() > 0) ? rate : BigDecimal.ZERO;
        if (r.signum() == 0) {
            BigDecimal net = scale(amount);
            return new TaxAmounts(net, BigDecimal.ZERO, BigDecimal.ZERO, net);
        }
        if (inclusive) {
            // The price already contains the tax: net = gross / (1 + r/100), tax = gross - net.
            BigDecimal gross = scale(amount);
            BigDecimal divisor = BigDecimal.ONE.add(r.divide(HUNDRED, 6, ROUNDING));
            BigDecimal net = gross.divide(divisor, SCALE, ROUNDING);
            return new TaxAmounts(net, r, gross.subtract(net), gross);
        }
        BigDecimal net = scale(amount);
        BigDecimal tax = net.multiply(r).divide(HUNDRED, SCALE, ROUNDING);
        return new TaxAmounts(net, r, tax, net.add(tax));
    }

    /**
     * Tax for one line HONOURING THE TENANT SWITCH — the entry point every channel should use.
     *
     * <p>When a tenant has not enabled sales tax, the whole amount is net and the tax is zero, whatever the
     * product's rate says. That switch is the thing the storefront's private copy forgot, and forgetting it
     * is how a quote and its invoice came to disagree.
     */
    public static TaxAmounts forLine(BigDecimal lineAmount, BigDecimal productRate,
                                     boolean taxEnabled, BigDecimal orgDefaultRate, boolean inclusive) {
        if (!taxEnabled) {
            BigDecimal net = scale(lineAmount != null ? lineAmount : BigDecimal.ZERO);
            return new TaxAmounts(net, BigDecimal.ZERO, BigDecimal.ZERO, net);
        }
        return compute(lineAmount, resolveRate(productRate, orgDefaultRate), inclusive);
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(SCALE, ROUNDING);
    }
}
