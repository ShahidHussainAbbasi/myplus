package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import com.myplus.business_service.entity.Purchase;

/**
 * DOC-INT C — is this purchase line one the bill already has?
 *
 * <h3>Why a rule in code and not a UNIQUE index</h3>
 * One supplier bill is SEVERAL {@code purchase} rows — "Save &amp; Add Another" keeps the vendor, the bill # and
 * the date, and clears only the line. A UNIQUE on (vendor, bill #) would refuse the second line of every real
 * bill. So the database narrows the question to the bill's own lines ({@code PurchaseRepo.findBillLinesScoped})
 * and this class decides "same line" — which is also what keeps the rule testable without a database.
 *
 * <h3>What "the same line" means</h3>
 * Same product AND same batch, where a blank batch equals a blank batch, compared trimmed and case-insensitively.
 * A different batch is a different line: a pharmacy routinely receives one product in two lots on one bill.
 * A VOID line is history, not a line.
 *
 * <p>The answer is a question for a person, not a refusal — the rare legitimate repeat exists — so the caller
 * holds the save and asks (the {@code CONFIRM} envelope), exactly as the supplier credit-limit warning does.
 */
public final class DuplicateBillLine {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private DuplicateBillLine() {}

    /** Trimmed text, or {@code null} when absent or blank — so blank and missing compare equal. */
    public static String normalise(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * The existing line this one repeats, if any.
     *
     * @param sameBillLines the rows already on this vendor's bill (any product)
     */
    public static Optional<Purchase> find(List<Purchase> sameBillLines, Long productId, String batchNo) {
        if (sameBillLines == null || productId == null) return Optional.empty();
        String batch = normalise(batchNo);
        for (Purchase p : sameBillLines) {
            if (p == null || "VOID".equals(p.getStatus())) continue;
            if (!productId.equals(p.getProductId())) continue;
            String other = normalise(p.getBatchNo());
            boolean sameBatch = (batch == null) ? other == null : batch.equalsIgnoreCase(other);
            if (sameBatch) return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * The sentence the operator reads. It names the bill, the vendor, the batch and WHEN the line was saved,
     * because "this already exists" alone does not let anyone decide whether it is a repeat or a second delivery.
     */
    public static String message(String bill, String vendorName, Purchase prior) {
        StringBuilder m = new StringBuilder("Bill ").append(bill);
        String vendor = normalise(vendorName);
        if (vendor != null) m.append(" from ").append(vendor);
        m.append(" already has this product");
        String batch = normalise(prior.getBatchNo());
        if (batch != null) m.append(" (batch ").append(batch).append(')');
        if (prior.getDated() != null) m.append(" — saved ").append(prior.getDated().format(DAY));
        if (prior.getQuantity() != null) m.append(", qty ").append(qty(prior.getQuantity()));
        return m.append(". Save this line again?").toString();
    }

    /** 10.0 → "10", 2.5 → "2.5". Through Float.toString so 0.1f reads as 0.1, not its binary expansion. */
    private static String qty(Float q) {
        return new BigDecimal(Float.toString(q)).stripTrailingZeros().toPlainString();
    }
}
