package com.myplus.marketplace.multiseller.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MKT-2-06 — a merchant seller's acceptance window by the value of its part (source R10.6 "terms may vary by … order
 * value"; slice doc {@code mkt-2-06-acceptance-by-value.md}). Pure: parsed from and written to one platform setting.
 *
 * <p>A part worth MORE than a rule's amount gets that rule's minutes; of several, the highest amount it is above. A part
 * above no rule gets the base window. The rules never touch the stock hold: the trade side holds a marketplace order's
 * stock for days, far longer than {@link #MAX_MINUTES}, so {@link AcceptanceTerms}' "hold outlives the window" holds.
 */
public record AcceptanceByValue(List<Tier> tiers) {

    public static final int MAX_TIERS = 5;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 60;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");

    /** Parts worth more than {@code above} get {@code minutes}. */
    public record Tier(BigDecimal above, int minutes) {
    }

    public AcceptanceByValue {
        tiers = List.copyOf(tiers);
    }

    public static AcceptanceByValue none() {
        return new AcceptanceByValue(List.of());
    }

    /**
     * Checked and sorted by amount. Refused, in a sentence, so the operator can fix it: a missing or non-positive amount,
     * minutes outside 1 to 60, the same amount twice, more than {@link #MAX_TIERS} rules.
     */
    public static AcceptanceByValue of(List<Tier> given) {
        List<Tier> in = given == null ? List.of() : given;
        if (in.size() > MAX_TIERS)
            throw new MarketplaceRuleException("TOO_MANY_TIERS", "At most 5 rules.");
        Set<BigDecimal> seen = new HashSet<>();
        List<Tier> out = new ArrayList<>();
        for (Tier t : in) {
            if (t == null || t.above() == null || t.above().signum() <= 0 || t.above().compareTo(MAX_AMOUNT) > 0)
                throw new MarketplaceRuleException("TIER_AMOUNT", "Each rule needs an amount above Rs 0, up to Rs 100,000,000.");
            if (t.minutes() < MIN_MINUTES || t.minutes() > MAX_MINUTES)
                throw new MarketplaceRuleException("TIER_MINUTES", "Each rule's minutes are 1 to 60.");
            BigDecimal a = t.above().setScale(2, java.math.RoundingMode.HALF_UP);
            if (!seen.add(a))
                throw new MarketplaceRuleException("TIER_DUPLICATE", "Two rules have the same amount: Rs "
                        + String.format(java.util.Locale.ROOT, "%,.0f", a) + ".");
            out.add(new Tier(a, t.minutes()));
        }
        out.sort(Comparator.comparing(Tier::above));
        return new AcceptanceByValue(out);
    }

    /** The stored form, {@code "100000.00:15;500000.00:30"}; empty for no rules. */
    public String format() {
        StringBuilder b = new StringBuilder();
        for (Tier t : tiers) {
            if (b.length() > 0) b.append(';');
            b.append(t.above().toPlainString()).append(':').append(t.minutes());
        }
        return b.toString();
    }

    /** The stored form back. Anything unreadable reads as no rules: the base window, the behaviour before this slice. */
    public static AcceptanceByValue parse(String stored) {
        if (stored == null || stored.isBlank()) return none();
        try {
            List<Tier> ts = new ArrayList<>();
            for (String part : stored.split(";")) {
                String[] kv = part.split(":");
                if (kv.length != 2) return none();
                ts.add(new Tier(new BigDecimal(kv[0].trim()), Integer.parseInt(kv[1].trim())));
            }
            return of(ts);
        } catch (RuntimeException e) {
            return none();
        }
    }

    /** Minutes for a part worth {@code value}: the rule with the highest amount the value is above, else {@code base}. */
    public int minutesFor(BigDecimal value, int base) {
        if (value == null) return base;
        int m = base;
        for (Tier t : tiers) if (value.compareTo(t.above()) > 0) m = t.minutes();   // sorted: the last match is the highest
        return m;
    }
}
