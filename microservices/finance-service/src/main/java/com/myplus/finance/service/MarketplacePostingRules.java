package com.myplus.finance.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.myplus.finance.dto.JournalLineDTO;

/**
 * MKT-1g — the journals MaxTheService's own books take from the marketplace settlement ledger. Pure (no Spring, no
 * repository), so each rule is a plain unit test. Slice: microservices/docs/slices/mkt-1g-settlement-payouts.md.
 *
 * <h3>One control account for both directions (ruling R-MKT-2)</h3>
 * The platform collects online payments; the seller's rider collects cash on delivery. Either way, what the operator
 * and a seller owe each other is ONE running balance, so the books carry it in ONE account, {@code 2400 Marketplace
 * Seller Balances}: a credit balance is money owed to sellers, a debit balance is commission sellers owe the
 * platform. Splitting it into a payable and a receivable would make every payout that nets the two a three-legged
 * journal, and the account would stop agreeing with the sum of the sellers' statements.
 *
 * <pre>
 *   MKT_SETTLEMENT  a line became payable          Dr 1010 Bank (cash the platform collected)
 *                                                  Cr 4500 Marketplace Commission (commission)
 *                                                  Cr/Dr 2400 the difference (owed to / by the seller)
 *   MKT_ADJUSTMENT  an operator's correction       +x: Dr 4510 / Cr 2400     −x: Dr 2400 / Cr 4510
 *   MKT_PAYOUT      the bank transfer to a seller  Dr 2400 / Cr 1010
 *   MKT_REMITTANCE  a seller paid what it owed     Dr 1010 / Cr 2400   (MKT-2d: cash on delivery the rider collected)
 * </pre>
 *
 * <p>The card receipt is booked when the line settles, net of any refund: Phase 1 posts nothing at capture, so a
 * refund before settlement never needs a reversing journal. Booking the receipt at capture is not built yet.
 */
public final class MarketplacePostingRules {

    static final String BANK = "1010";
    static final String SELLER_BALANCES = "2400";
    static final String COMMISSION = "4500";
    static final String ADJUSTMENTS = "4510";

    private MarketplacePostingRules() { }

    /**
     * @param collected  what the PLATFORM holds for this line (the card payment net of refunds; zero for cash on
     *                   delivery, which the seller's rider holds)
     * @param commission the operator's commission on the line
     */
    public static List<JournalLineDTO> settlement(BigDecimal collected, BigDecimal commission) {
        BigDecimal cash = nz(collected), fee = nz(commission);
        if (cash.signum() < 0 || fee.signum() < 0)
            throw new IllegalArgumentException("A marketplace settlement cannot carry a negative amount.");
        List<JournalLineDTO> lines = new ArrayList<>();
        if (cash.signum() > 0) lines.add(dr(BANK, cash));
        if (fee.signum() > 0) lines.add(cr(COMMISSION, fee));
        BigDecimal toSeller = cash.subtract(fee);
        if (toSeller.signum() > 0) lines.add(cr(SELLER_BALANCES, toSeller));
        else if (toSeller.signum() < 0) lines.add(dr(SELLER_BALANCES, toSeller.negate()));
        return lines;
    }

    /** {@code amount} is signed from the seller's side: positive = the seller is owed more. */
    public static List<JournalLineDTO> adjustment(BigDecimal amount) {
        BigDecimal a = nz(amount);
        if (a.signum() == 0) throw new IllegalArgumentException("A marketplace adjustment needs an amount.");
        return a.signum() > 0 ? List.of(dr(ADJUSTMENTS, a), cr(SELLER_BALANCES, a))
                : List.of(dr(SELLER_BALANCES, a.negate()), cr(ADJUSTMENTS, a.negate()));
    }

    public static List<JournalLineDTO> payout(BigDecimal amount) {
        BigDecimal a = nz(amount);
        if (a.signum() <= 0) throw new IllegalArgumentException("A marketplace payout must be more than zero.");
        return List.of(dr(SELLER_BALANCES, a), cr(BANK, a));
    }

    /** MKT-2d — the seller paid the platform what it owed for cash orders: the debit balance on 2400 comes down. */
    public static List<JournalLineDTO> remittance(BigDecimal amount) {
        BigDecimal a = nz(amount);
        if (a.signum() <= 0) throw new IllegalArgumentException("A seller's payment to the marketplace must be more than zero.");
        return List.of(dr(BANK, a), cr(SELLER_BALANCES, a));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static JournalLineDTO dr(String code, BigDecimal amt) {
        return JournalLineDTO.builder().accountCode(code).debit(amt).build();
    }

    private static JournalLineDTO cr(String code, BigDecimal amt) {
        return JournalLineDTO.builder().accountCode(code).credit(amt).build();
    }
}
